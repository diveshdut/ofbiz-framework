/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.manufacturing.jobshopmgt

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
import java.sql.Timestamp

/**
 * Automated inventory reservation for all components of a production run.
 *
 * This service is typically triggered via a SECA rule when a production run status changes
 * to 'PRUN_DOC_PRINTED'. However, it is designed to be safe to run at any stage of the
 * production run lifecycle:
 *
 * 1. **Idempotency**: It is safe to re-run multiple times. If components are already
 *    fully reserved, the service will perform no actions.
 * 2. **Manual-First Support**: It respects existing manual reservations. If a user
 *    already reserved a specific lot for a task, the automation will skip that component
 *    or only reserve the remaining requirement balance.
 * 3. **Partial Recovery**: If a production run is already 'In-Progress' and some
 *    items have been issued, it correctly accounts for 'totalIssued' and only reserves
 *    the remaining unmet requirement.
 *
 * It performs the following steps:
 * 1. Resolves all child routing tasks (WorkEffort) associated with the production run.
 * 2. Batches fetches all current issuances and reservations for all tasks in two queries.
 * 3. Iterates through material requirements and calls 'reserveWorkEffortInventoryItem'
 *    with 'requireInventory=N' to handle best-effort reservations and shortage tracking.
 */
Map autoReserveWorkEffortInventory() {
    String productionRunId = parameters.productionRunId ?: parameters.workEffortId
    if (!productionRunId) {
        return ServiceUtil.returnError('Production Run ID is required for auto-reservation')
    }

    GenericValue productionRun = from('WorkEffort').where('workEffortId', productionRunId).queryOne()
    if (!productionRun) {
        return ServiceUtil.returnError('Production Run not found: ' + productionRunId)
    }

    String facilityId = productionRun.facilityId
    if (!facilityId) {
        return success()
    }

    // 1. Resolve all Child Tasks
    List taskIds = from('WorkEffort')
        .where('workEffortParentId', productionRunId, 'workEffortTypeId', 'PROD_ORDER_TASK')
        .getFieldList('workEffortId')
    if (!taskIds) {
        return success()
    }

    // 2. Resolve all component requirements (WorkEffortGoodStandard) for these tasks
    List wegsList = from('WorkEffortGoodStandard')
        .where(EntityCondition.makeCondition('workEffortId', EntityOperator.IN, taskIds),
               EntityCondition.makeCondition('workEffortGoodStdTypeId', 'PRUNT_PROD_NEEDED'))
        .queryList()
    if (!wegsList) {
        return success()
    }

    // 3. Batch fetch current state
    // We retrieve all issuances and reservations for the entire task set in two queries.
    Map issuedMap = from('WorkEffortAndInventoryAssign')
        .where(EntityCondition.makeCondition('workEffortId', EntityOperator.IN, taskIds))
        .queryList()
        .groupBy { it.workEffortId + '_' + it.productId }
        .collectEntries { key, list -> [key, list.sum { it.getBigDecimal('quantity') ?: BigDecimal.ZERO }] }

    Map reservedMap = from('WorkEffortInvResAndItem')
        .where(EntityCondition.makeCondition('workEffortId', EntityOperator.IN, taskIds))
        .queryList()
        .groupBy { it.workEffortId + '_' + it.productId }
        .collectEntries { key, list -> [key, list.sum { it.getBigDecimal('quantity') ?: BigDecimal.ZERO }] }

    // 4. Component Reservation Loop
    for (GenericValue wegs : wegsList) {
        String productId = wegs.productId
        String taskId = wegs.workEffortId
        String lookupKey = taskId + '_' + productId

        // 4.1 Get totals from the pre-fetched batch maps
        BigDecimal totalReserved = reservedMap[lookupKey] ?: BigDecimal.ZERO
        BigDecimal totalIssued = issuedMap[lookupKey] ?: BigDecimal.ZERO

        // 4.2 Calculate remaining balance (Automation safety)
        BigDecimal qtyNeeded = wegs.getBigDecimal('estimatedQuantity').subtract(totalReserved).subtract(totalIssued)
        if (qtyNeeded <= 0) {
            continue
        }

        // 4.3 Trigger reservation, letting the core engine handle shortages (requireInventory=N)
        Map serviceResult = runService('reserveWorkEffortInventoryItem', [
            workEffortId: taskId,
            productId: productId,
            facilityId: facilityId,
            quantity: qtyNeeded,
            reserveOrderEnumId: parameters.reserveOrderEnumId
        ])
        if (ServiceUtil.isError(serviceResult)) {
            return serviceResult
        }
    }
    return success()
}

/**
 * Persistence service for the WorkEffortInvRes entity.
 * This service is called by the core inventory reservation engine
 * reservation succeeds. It handles the low-level DB inserts and updates to track exactly which
 * physical inventory items were reserved for which production order task.
 */
Map reserveWorkEffortInventory() {
    String workEffortId = parameters.workEffortId
    String inventoryItemId = parameters.inventoryItemId
    String productId = parameters.productId

    // Self-heal: If productId is missing (e.g. from a slightly broken SECA), recover it from the record
    if (!productId && inventoryItemId) {
        GenericValue item = from('InventoryItem').where('inventoryItemId', inventoryItemId).cache().queryOne()
        productId = item?.productId
        logInfo("reserveWorkEffortInventory: Recovered missing productId [${productId}] from Item [${inventoryItemId}]")
    }

    // VALIDATION: Strict Product Identity Guard
    if (inventoryItemId) {
        GenericValue item = from('InventoryItem').where('inventoryItemId', inventoryItemId).cache().queryOne()
        if (item && productId && item.productId != productId) {
            String errMsg = "Product Identity Mismatch: Item [${inventoryItemId}] belongs to [${item.productId}], but you are reserving for [${productId}]."
            logError("reserveWorkEffortInventory - FAILED: ${errMsg}")
            return error(errMsg)
        }
    }

    GenericValue existing = from('WorkEffortInvRes')
        .where('workEffortId', workEffortId, 'inventoryItemId', inventoryItemId, 'productId', productId)
        .queryOne()

    Map serviceResult = [:]
    if (existing) {
        BigDecimal newQty = (existing.getBigDecimal('quantity') ?: BigDecimal.ZERO).add(parameters.quantity ?: BigDecimal.ZERO)
        BigDecimal newQtyNotAvailable = (existing.getBigDecimal('quantityNotAvailable') ?: BigDecimal.ZERO).add(parameters.quantityNotAvailable ?: BigDecimal.ZERO)
        serviceResult = runService('updateWorkEffortInvRes', [*: parameters, productId: productId, quantity: newQty, quantityNotAvailable: newQtyNotAvailable])
    } else {
        serviceResult = runService('createWorkEffortInvRes', [*: parameters, productId: productId])
    }
    
    if (ServiceUtil.isError(serviceResult)) return serviceResult
    return success()
}

/**
 * Manufacturing-specific reservation wrapper (Decoupled Extension Point).
 *
 * This service acts as a domain-specific bridge to the core inventory engine.
 *
 * Why use a wrapper instead of calling 'reserveProductInventoryByFacility' directly?
 * 1. **Manufacturing Defaults**: Centrally enforces 'requireInventory=N' (backordering)
 *    for all production-related reservations.
 * 2. **Architectural Flexibility**: Provides a clean, decoupled hook for manufacturing-specific
 *    business rules (e.g., custom lot selection logic, restricting reservations by task
 *    status, or validating based on production run type).
 * 3. **Future Proofing**: Allows implementing complex domain logic that would be
 *    inappropriate to place in the generic core inventory engine.
 * 4. **SECA Isolation**: Enables triggering manufacturing-specific actions (logs,
 *    notifications, or integrations) specifically for production orders.
 */
Map reserveWorkEffortInventoryItem() {
    String strategy = parameters.reserveOrderEnumId ?: 'INVRO_FIFO_REC'
    String inventoryItemId = parameters.inventoryItemId
    String productId = parameters.productId
    BigDecimal qty = parameters.quantity ?: 0.0

    // VALIDATION: Strict Product Identity Guard
    if (inventoryItemId) {
        GenericValue item = from('InventoryItem').where('inventoryItemId', inventoryItemId).cache().queryOne()
        if (item && productId && item.productId != productId) {
            String errMsg = "Product Identity Mismatch: Item [${inventoryItemId}] is for product [${item.productId}], but the task requires [${productId}]."
            logError("reserveWorkEffortInventoryItem - FAILED: ${errMsg}")
            return error(errMsg)
        }

        /*
         * DIRECT RESERVATION OVERRIDE:
         * If a specific inventory item is requested, we bypass the generic reservation engine (Minilang)
         * which might skip the item if its ATP is temporarily non-positive.
         * For manufacturing, we trust the caller's item selection and record the reservation directly.
         */
        // Calculate QNA (Quantity Not Available / Debt) if we are in Strict mode
        // Note: For now we assume the full quantity is reserved.
        Map resCtx = [*: parameters, updateInventoryDetail: true]
        Map serviceResult = runService('reserveWorkEffortInventory', resCtx)
        if (ServiceUtil.isError(serviceResult)) return serviceResult
        
        return success(quantityNotReserved: 0.0)
    }

    Map reserveCtx = [*: parameters,
                      reserveOrderEnumId: strategy,
                      requireInventory: (parameters.requireInventory ?: 'N')]
    Map serviceResult = runService('reserveProductInventoryByFacility', reserveCtx)
    return serviceResult
}

/**
 * SECA condition service to gate automated reservations.
 * It checks the 'autoReservePrun' flag on the Facility associated with the production run.
 * If the flag is set to 'Y', automated reservations are permitted to proceed.
 */
Map checkProductionRunFacilityAutoReserve() {
    String productionRunId = parameters.productionRunId ?: parameters.serviceContext?.productionRunId
    GenericValue header = null

    if (productionRunId) {
        header = from('WorkEffort').where('workEffortId', productionRunId).queryOne()
    } else {
        String workEffortId = parameters.workEffortId ?: parameters.serviceContext?.workEffortId
        if (workEffortId) {
            GenericValue workEffort = from('WorkEffort').where('workEffortId', workEffortId).queryOne()
            if (workEffort) {
                header = (workEffort.workEffortTypeId == 'PROD_ORDER_HEADER') ?
                        workEffort : from('WorkEffort').where('workEffortId', workEffort.workEffortParentId).queryOne()
            }
        }
    }

    if (!header || !header.facilityId) return success(conditionReply: false)

    GenericValue facility = from('Facility').where('facilityId', header.facilityId).queryOne()
    boolean reply = (facility && facility.autoReservePrun == 'Y')
    return success(conditionReply: reply)
}

/**
 * Releases, cancels, or satisfies an existing inventory reservation.
 *
 * Supports both partial and full releases:
 * - If 'quantity' is less than the current reservation, it performs a partial release by
 *   decrementing the WorkEffortInvRes record (e.g., Releasing 5 units from a 10-unit reservation).
 * - If 'quantity' is null or >= current reservation, it performs a full release by
 *   deleting the WorkEffortInvRes record.
 *
 * SATISFACTION vs CANCELLATION (appendInventoryItemDetail flag):
 * - Cancellation (Default: true): The reservation is removed without being consumed by production. 
 *   The system creates an InventoryItemDetail record to restore the Available-To-Promise (ATP)
 *   count, as the stock is now back in the free pool.
 * - Satisfaction/Consumption (false): The reservation is removed because physical stock was 
 *   already issued to the task. In this case, we suppress ATP restoration because the physical 
 *   issuance already accounted for the availability change. Using this flag prevents "ATP Leaks".
 *
 * It performs a reversal of the reservation by:
 * 1. Validating the current reservation quantity for the task/lot combination.
 * 2. Updating or deleting the WorkEffortInvRes record depending on the quantity to release.
 * 3. Conditionally restoring ATP based on the appendInventoryItemDetail flag.
 */
Map releaseProductionRunTaskComponent() {
    String workEffortId = parameters.workEffortId
    String productId = parameters.productId
    String inventoryItemId = parameters.inventoryItemId
    BigDecimal quantityToRelease = (BigDecimal) parameters.quantity

    // Attempt direct lookup if productId is provided; otherwise proceed straight to broad lookup
    GenericValue res = productId ? from('WorkEffortInvRes')
        .where(workEffortId: workEffortId, productId: productId, inventoryItemId: inventoryItemId)
        .queryOne() : null

    /*
     * SELF-HEALING SAFETY NET:
     * Due to the composite Primary Key of WorkEffortInvRes (which includes productId), a "Direct Match"
     * can fail if there is any data drift (e.g., product aliases) or if productId is missing from parameters.
     */
    if (!res) {
        logWarning("releaseProductionRunTaskComponent - No direct match found (Product missing or mismatch). Running Broad Lookup for Task [${workEffortId}] Item [${inventoryItemId}]...")
        List broadSearch = from('WorkEffortInvRes')
            .where(workEffortId: workEffortId, inventoryItemId: inventoryItemId)
            .queryList()
            
        if (broadSearch.size() == 1) {
            res = broadSearch[0]
            logWarning("releaseProductionRunTaskComponent - [SELF-HEALING]: Found orphan reservation. Record Product: ${res.productId}, Target Product: ${productId}. Proceeding with release.")
        } else if (broadSearch.size() > 1) {
            logError("releaseProductionRunTaskComponent - CRITICAL: Found ${broadSearch.size()} ambiguous reservations for Task-Item pair. Cannot safely determine which to release without Product ID. Skipping.")
        } else {
            logWarning("releaseProductionRunTaskComponent - No reservation found even in broad search. Nothing to clean up.")
        }
    }

    if (res) {
        BigDecimal reservedQty = res.getBigDecimal('quantity') ?: BigDecimal.ZERO
        BigDecimal currentQna = res.getBigDecimal('quantityNotAvailable') ?: BigDecimal.ZERO
        BigDecimal releaseQty = (quantityToRelease != null && quantityToRelease < reservedQty) ? quantityToRelease : reservedQty
        
        // Calculate QNA change (Debt is always released first)
        BigDecimal newQna = (releaseQty < reservedQty) ? currentQna.subtract(releaseQty).max(BigDecimal.ZERO) : BigDecimal.ZERO
        BigDecimal qnaReleased = currentQna.subtract(newQna)
        BigDecimal physicalReleased = releaseQty.subtract(qnaReleased)
        // Low-level inventory adjustment
        GenericValue inventoryItem = from('InventoryItem').where('inventoryItemId', inventoryItemId).queryOne()
        if (inventoryItem) {
            if (inventoryItem.inventoryItemTypeId == 'SERIALIZED_INV_ITEM') {
                Map serviceResult = runService('updateInventoryItem', [inventoryItemId: inventoryItemId, statusId: 'INV_AVAILABLE'])
                if (ServiceUtil.isError(serviceResult)) return serviceResult
            } else {
                /*
                 * SATISFACTION vs CANCELLATION:
                 * - Cancellation (Default): Reservation is removed without consumption. ATP must be restored.
                 * - Satisfaction/Consumption (appendInventoryItemDetail=false): Reservation is removed because physical 
                 *   stock was already issued. ATP must NOT be restored because the physical issuance 
                 *   already accounted for the reduction and the reservation disappearance.
                 */
                boolean shouldAppend = !(parameters.appendInventoryItemDetail in [false, 'N', 'false', 'n'])
                if (shouldAppend && physicalReleased > 0) {
                    Map serviceResult = runService('createInventoryItemDetail', [
                        inventoryItemId: inventoryItemId,
                        workEffortId: workEffortId,
                        availableToPromiseDiff: physicalReleased
                    ])
                    if (ServiceUtil.isError(serviceResult)) return serviceResult
                }
            }
        }

        if (releaseQty < reservedQty) {
            Map serviceResult = runService('updateWorkEffortInvRes', [
                workEffortId: workEffortId,
                productId: res.productId,
                inventoryItemId: inventoryItemId,
                quantity: reservedQty.subtract(releaseQty),
                quantityNotAvailable: newQna
            ])
            if (ServiceUtil.isError(serviceResult)) return serviceResult
        } else {
            Map serviceResult = runService('deleteWorkEffortInvRes', [
                workEffortId: workEffortId,
                productId: res.productId, // Use the record's actual productId
                inventoryItemId: inventoryItemId
            ])
            if (ServiceUtil.isError(serviceResult)) return serviceResult
        }
    } else {
        logError("releaseProductionRunTaskComponent - CRITICAL: No reservation found for Task [${workEffortId}] on Item [${inventoryItemId}]. Skipping cleanup.")
    }
    return success()
}

/**
 * Utility service to calculate current issued and reserved totals for a component.
 * This is primarily used by the UI components to display accurate "Remaining Balance"
 * information and to provide suggested quantities when manually adding new reservations.
 */
Map getTaskQuantities() {
    String workEffortId = parameters.workEffortId
    String productId = parameters.productId

    BigDecimal issued = from('WorkEffortAndInventoryAssign')
        .where('workEffortId', workEffortId, 'productId', productId)
        .queryList().sum { it.getBigDecimal('quantity') ?: BigDecimal.ZERO } ?: BigDecimal.ZERO

    List resList = from('WorkEffortInvRes')
        .where('workEffortId', workEffortId, 'productId', productId)
        .queryList()

    BigDecimal reserved = resList.sum { it.getBigDecimal('quantity') ?: BigDecimal.ZERO } ?: BigDecimal.ZERO
    BigDecimal shortfall = resList.sum { it.getBigDecimal('quantityNotAvailable') ?: BigDecimal.ZERO } ?: BigDecimal.ZERO
    BigDecimal netReserved = (reserved - shortfall).max(BigDecimal.ZERO)

    GenericValue wegs = from('WorkEffortGoodStandard')
        .where('workEffortId', workEffortId, 'productId', productId, 'workEffortGoodStdTypeId', 'PRUNT_PROD_NEEDED')
        .queryFirst()
    BigDecimal estimated = wegs?.getBigDecimal('estimatedQuantity') ?: BigDecimal.ZERO
    BigDecimal remaining = (estimated - issued - netReserved).max(BigDecimal.ZERO)
    BigDecimal unaccounted = (estimated - issued - reserved).max(BigDecimal.ZERO)

    return success(issuedQuantity: issued,
                   reservedQuantity: reserved,
                   netReservedQuantity: netReserved,
                   shortfallQuantity: shortfall,
                   unaccountedQuantity: unaccounted,
                   remainingQuantity: remaining,
                   requiredQuantity: estimated)
}

Map getProductionRunTaskForceIssueImpact() {
    String workEffortId = parameters.workEffortId
    String productIdParam = parameters.productId
    String reserveOrderEnumId = parameters.reserveOrderEnumId ?: "INVRO_FIFO_REC"
    if (!workEffortId) {
        return ServiceUtil.returnError('Task ID is required for impact analysis')
    }

    GenericValue task = from('WorkEffort').where('workEffortId', workEffortId).queryOne()
    if (!task) {
        return ServiceUtil.returnError('Task not found: ' + workEffortId)
    }

    // Find the actual Production Run identity and resolve facility
    GenericValue productionRun = null
    String facilityId = null
    GenericValue currentEffort = task
    while (currentEffort) {
        if (!facilityId && currentEffort.facilityId) {
            facilityId = currentEffort.facilityId
        }
        if (currentEffort.workEffortTypeId == 'PROD_RUN') {
            productionRun = currentEffort
        }
        if (currentEffort.workEffortParentId) {
            currentEffort = from('WorkEffort').where('workEffortId', currentEffort.workEffortParentId).cache().queryOne()
        } else {
            break
        }
    }
    
    // If we didn't find a PROD_RUN type parent, use the top-most effort as fallback identity
    if (!productionRun) productionRun = currentEffort 
    
    // Policy Check: Audit allowInventoryTheft flag
    if (facilityId) {
        GenericValue facility = from("Facility").where("facilityId", facilityId).cache().queryOne()
        if (facility && facility.allowInventoryTheft != 'Y') {
            logInfo("Policy Check Failed: allowInventoryTheft is 'N' for Facility [${facilityId}]. Blocking theft analysis.")
            Map result = ServiceUtil.returnSuccess()
            result.policyViolation = "Inventory re-allocation (theft) is forbidden for Facility [${facilityId}]. Please adjust facility policies to allow this operation."
            return result
        }
    }

    List impactList = []
    List inventoryIssuePlan = []
    BigDecimal totalNeededQuantity = BigDecimal.ZERO

    // 1. Resolve component requirements (WorkEffortGoodStandard)
    Map wegsCond = [workEffortId: workEffortId, workEffortGoodStdTypeId: 'PRUNT_PROD_NEEDED']
    if (productIdParam) {
        wegsCond.productId = productIdParam
    }
    List wegsList = from('WorkEffortGoodStandard')
        .where(wegsCond)
        .queryList()

    for (GenericValue wegs : wegsList) {
        String productId = wegs.productId
        BigDecimal estimated = wegs.getBigDecimal('estimatedQuantity') ?: BigDecimal.ZERO
        totalNeededQuantity = totalNeededQuantity.add(estimated)

        // Waterfall State for this component
        BigDecimal balanceToAccountFor = estimated

        // MAP: Track physical capacity to prevent double-counting across stages
        Map<String, BigDecimal> remainingQohPerItem = [:]

        // Stage 1: Account for existing issuances (Stock already out the door)
        BigDecimal issued = from('WorkEffortAndInventoryAssign')
            .where('workEffortId', workEffortId, 'productId', productId)
            .queryList().sum { it.getBigDecimal('quantity') ?: BigDecimal.ZERO } ?: BigDecimal.ZERO
        
        if (issued > 0) {
            inventoryIssuePlan << [
                productId: productId,
                inventoryItemId: 'ALREADY_ISSUED',
                quantity: issued,
                type: 'ALREADY_ISSUED'
            ]
            balanceToAccountFor = balanceToAccountFor.subtract(issued)
        }
        if (balanceToAccountFor <= 0) continue

        // Stage 2: Account for OUR existing reservations (Stock already set aside for us)
        List taskReservations = from('WorkEffortInvResAndItem')
            .where(workEffortId: workEffortId, productId: productId)
            .queryList()
        
        for (GenericValue res : taskReservations) {
            if (balanceToAccountFor <= 0) break
            BigDecimal netRes = (res.getBigDecimal('quantity') ?: 0).subtract(res.getBigDecimal('quantityNotAvailable') ?: 0)
            BigDecimal qoh = res.getBigDecimal('quantityOnHandTotal') ?: 0
            if (netRes > 0 && qoh > 0) {
                if (remainingQohPerItem[res.inventoryItemId] == null) remainingQohPerItem[res.inventoryItemId] = qoh
                
                BigDecimal availablePhysics = remainingQohPerItem[res.inventoryItemId]
                BigDecimal qtyToTake = balanceToAccountFor.min(netRes).min(availablePhysics)
                
                if (qtyToTake > 0) {
                    inventoryIssuePlan << [
                        productId: productId,
                        inventoryItemId: res.inventoryItemId,
                        quantity: qtyToTake,
                        lotId: res.get('lotId'),
                        type: 'TASK_RESERVATION'
                    ]
                    balanceToAccountFor = balanceToAccountFor.subtract(qtyToTake)
                    remainingQohPerItem[res.inventoryItemId] = remainingQohPerItem[res.inventoryItemId].subtract(qtyToTake)
                }
            }
        }
        if (balanceToAccountFor <= 0) continue

        // TRINITY SYNC: Ensure candidate pool is logically consistent before analysis.
        // ROLE: This service acts as a "Ledger Flush" or "Baseline Sync". It force-recalculates
        // ATP to ensure that recent physical issuances, thefts, or manual overrides are 
        // fully synchronized with the logical pool. This prevents "ATP Drift" and 
        // ensure the Auditor/Satisfier operates on the absolute truth of physical reality.
        from('InventoryItem').where(productId: productId, facilityId: facilityId).queryList().each {
            runService("trinitySync", [inventoryItemId: it.inventoryItemId])
        }

        // Stage 3: Pick from Free Pool (Physical unreserved stock)
        List freeStock = from("InventoryItem")
            .where("productId", productId, "facilityId", facilityId)
            .queryList()
            .findAll { (it.availableToPromiseTotal ?: 0) > 0 && (it.quantityOnHandTotal ?: 0) > 0 }
        
        // Sort free stock by strategy
        if (reserveOrderEnumId == "INVRO_LIFO_REC") {
            freeStock.sort { a, b -> (b.datetimeReceived?.time ?: 0L) <=> (a.datetimeReceived?.time ?: 0L) }
        } else if (reserveOrderEnumId == "INVRO_FIFO_EXP") {
            freeStock.sort { a, b -> (a.expireDate?.time ?: 0L) <=> (b.expireDate?.time ?: 0L) }
        } else if (reserveOrderEnumId == "INVRO_LIFO_EXP") {
            freeStock.sort { a, b -> (b.expireDate?.time ?: 0L) <=> (a.expireDate?.time ?: 0L) }
        } else if (reserveOrderEnumId == "INVRO_COST_DESC") {
            freeStock.sort { a, b -> (b.unitCost ?: 0) <=> (a.unitCost ?: 0) }
        } else if (reserveOrderEnumId == "INVRO_COST_ASC") {
            freeStock.sort { a, b -> (a.unitCost ?: 0) <=> (b.unitCost ?: 0) }
        } else { // Default: FIFO Received (INVRO_FIFO_REC)
            freeStock.sort { a, b -> (a.datetimeReceived?.time ?: 0L) <=> (b.datetimeReceived?.time ?: 0L) }
        }

        for (GenericValue item : freeStock) {
            if (balanceToAccountFor <= 0) break
            if (remainingQohPerItem[item.inventoryItemId] == null) remainingQohPerItem[item.inventoryItemId] = item.getBigDecimal('quantityOnHandTotal') ?: 0
            
            BigDecimal atp = item.getBigDecimal('availableToPromiseTotal') ?: 0
            BigDecimal availablePhysics = remainingQohPerItem[item.inventoryItemId]
            
            // We can only take what is BOTH available logically (ATP) and physically (remaining QOH)
            BigDecimal qtyToTake = balanceToAccountFor.min(atp).min(availablePhysics)
            
            if (qtyToTake > 0) {
                inventoryIssuePlan << [
                    productId: productId,
                    inventoryItemId: item.inventoryItemId,
                    quantity: qtyToTake,
                    type: 'POOL_STOCK'
                ]
                balanceToAccountFor = balanceToAccountFor.subtract(qtyToTake)
                remainingQohPerItem[item.inventoryItemId] = remainingQohPerItem[item.inventoryItemId].subtract(qtyToTake)
            }
        }
        if (balanceToAccountFor <= 0) continue

        // Stage 4: Reallocate from others (The "Stolen" inventory)
        List potentialVictims = []

        // 4.1 Production Task Reservations
        from('WorkEffortInvResAndItem')
            .where(EntityCondition.makeCondition([
                EntityCondition.makeCondition(productId: productId, facilityId: facilityId),
                EntityCondition.makeCondition('workEffortId', EntityOperator.NOT_EQUAL, workEffortId)
            ], EntityOperator.AND))
            .queryList()
            .each { res ->
                if (res.get('productId') != productId) return 
                BigDecimal netRes = (res.getBigDecimal('quantity') ?: 0).subtract(res.getBigDecimal('quantityNotAvailable') ?: 0)
                BigDecimal qoh = res.getBigDecimal('quantityOnHandTotal') ?: 0
                if (netRes > 0 && qoh > 0) {
                    potentialVictims << [
                        productId: res.get('productId'),
                        impactType: 'Production Task',
                        impactId: res.workEffortId,
                        parentWorkEffortId: res.workEffortParentId,
                        inventoryItemId: res.inventoryItemId,
                        datetimeReceived: res.datetimeReceived,
                        expireDate: res.expireDate,
                        unitCost: res.getBigDecimal('unitCost') ?: 0,
                        netQuantity: netRes,
                        qoh: qoh
                    ]
                }
            }

        // 4.2 Sales Order Reservations
        from('OrderItemShipGrpInvResAndItem')
            .where('productId', productId, 'facilityId', facilityId)
            .queryList()
            .each { res ->
                if (res.get('productId') != productId) return 
                BigDecimal netRes = (res.getBigDecimal('quantity') ?: 0).subtract(res.getBigDecimal('quantityNotAvailable') ?: 0)
                BigDecimal qoh = res.getBigDecimal('quantityOnHandTotal') ?: 0
                if (netRes > 0 && qoh > 0) {
                    potentialVictims << [
                        productId: res.get('productId'),
                        impactType: 'Sales Order',
                        impactId: res.orderId,
                        orderItemSeqId: res.orderItemSeqId,
                        shipGroupSeqId: res.shipGroupSeqId,
                        parentWorkEffortId: null,
                        inventoryItemId: res.inventoryItemId,
                        datetimeReceived: res.datetimeReceived,
                        expireDate: res.expireDate,
                        unitCost: res.getBigDecimal('unitCost') ?: 0,
                        netQuantity: netRes,
                        qoh: qoh
                    ]
                }
            }

        // Sort victims based on strategy
        if (reserveOrderEnumId == "INVRO_LIFO_REC") {
            potentialVictims.sort { a, b -> (b.datetimeReceived?.time ?: 0L) <=> (a.datetimeReceived?.time ?: 0L) }
        } else if (reserveOrderEnumId == "INVRO_FIFO_EXP") {
            potentialVictims.sort { a, b -> (a.expireDate?.time ?: 0L) <=> (b.expireDate?.time ?: 0L) }
        } else if (reserveOrderEnumId == "INVRO_LIFO_EXP") {
            potentialVictims.sort { a, b -> (b.expireDate?.time ?: 0L) <=> (a.expireDate?.time ?: 0L) }
        } else if (reserveOrderEnumId == "INVRO_COST_DESC") {
            potentialVictims.sort { a, b -> (b.unitCost ?: 0) <=> (a.unitCost ?: 0) }
        } else if (reserveOrderEnumId == "INVRO_COST_ASC") {
            potentialVictims.sort { a, b -> (a.unitCost ?: 0) <=> (b.unitCost ?: 0) }
        } else { // Default: FIFO Received (INVRO_FIFO_REC)
            potentialVictims.sort { a, b -> (a.datetimeReceived?.time ?: 0L) <=> (b.datetimeReceived?.time ?: 0L) }
        }

        for (Map victim : potentialVictims) {
            if (balanceToAccountFor <= 0) break
            
            if (remainingQohPerItem[victim.inventoryItemId] == null) remainingQohPerItem[victim.inventoryItemId] = victim.qoh
            BigDecimal availablePhysics = remainingQohPerItem[victim.inventoryItemId]
            
            // The "Stolen" amount is capped by what the victim has AND what is physically left on the shelf
            BigDecimal stolen = balanceToAccountFor.min(victim.netQuantity).min(availablePhysics)
            
            if (stolen > 0) {
                impactList << [
                    productId: victim.productId,
                    impactType: victim.impactType,
                    impactId: victim.impactId,
                    orderItemSeqId: victim.orderItemSeqId,
                    shipGroupSeqId: victim.shipGroupSeqId,
                    parentWorkEffortId: victim.parentWorkEffortId,
                    inventoryItemId: victim.inventoryItemId,
                    impactQuantity: stolen,
                    quantity: stolen,
                    type: 'STOLEN',
                    victimWorkEffortId: victim.impactId
                ]
                balanceToAccountFor = balanceToAccountFor.subtract(stolen)
                remainingQohPerItem[victim.inventoryItemId] = remainingQohPerItem[victim.inventoryItemId].subtract(stolen)
            }
        }
    }
    return success(inventoryIssuePlan: inventoryIssuePlan, impactList: impactList, totalNeededQuantity: totalNeededQuantity, facilityId: facilityId)
}

/**
 * Performs Move-Then-Issue Strategy using the impact analysis as context.
 */
Map reallocateAndIssueInventory() {
    String workEffortId = parameters.workEffortId
    String productId = parameters.productId
    String reserveOrderEnumId = parameters.reserveOrderEnumId ?: 'INVRO_FIFO_REC'
    List inventoryIssuePlan = parameters.inventoryIssuePlan ?: []
    List impactList = parameters.impactList ?: []
    String facilityId = parameters.facilityId
    String productionRunId = parameters.productionRunId

    // Self-healing: If context is STILL missing, re-calculate it (Backend-only or fallback)
    if (!inventoryIssuePlan) {
        Map impactResult = runService('getProductionRunTaskForceIssueImpact', [
            workEffortId: workEffortId,
            productId: productId,
            reserveOrderEnumId: reserveOrderEnumId
        ])
        if (ServiceUtil.isError(impactResult)) return impactResult
        if (impactResult.policyViolation) return ServiceUtil.returnError("Policy Violation: ${impactResult.policyViolation}")
        inventoryIssuePlan = (List<Map>) impactResult.inventoryIssuePlan
        impactList = (List<Map>) impactResult.impactList
        if (!facilityId) {
            facilityId = (String) impactResult.facilityId
        }
    }

    // Resolve facilityId and productionRunId if still missing (Direct call fallback)
    if (!facilityId || !productionRunId) {
        GenericValue task = from('WorkEffort').where('workEffortId', workEffortId).queryOne()
        GenericValue currentEffort = task
        while (currentEffort) {
            if (!facilityId) facilityId = currentEffort.facilityId
            if (!productionRunId && currentEffort.workEffortTypeId == 'PRODUCTION_RUN') {
                productionRunId = currentEffort.workEffortId
            }
            if (currentEffort.workEffortParentId) {
                currentEffort = from('WorkEffort').where('workEffortId', currentEffort.workEffortParentId).cache().queryOne()
            } else {
                break
            }
        }
    }

    // API Safety: Policy Enforcement
    // If there's an impactList (theft is happening) and allowInventoryTheft is 'N', block it.
    if (facilityId && impactList) {
        GenericValue facility = from("Facility").where("facilityId", facilityId).cache().queryOne()
        if (facility && facility.allowInventoryTheft == 'N') {
            logInfo("API Policy Check Failed: allowInventoryTheft is 'N' for Facility [${facilityId}]. Blocking re-allocation.")
            return ServiceUtil.returnError("Policy Violation: Inventory re-allocation (theft) is forbidden for Facility [${facilityId}].")
        }
    }
    // MINIMUM LOGIC GUARD: If the entire plan consists only of already issued items, short-circuit everything
    List allItems = (inventoryIssuePlan ?: []) + (impactList ?: [])
    boolean hasActionableItems = allItems.any { it.get('type') != 'ALREADY_ISSUED' && it.get('inventoryItemId') != 'ALREADY_ISSUED' }
    
    if (!hasActionableItems && allItems) {
        return success(inventoryIssuePlan: inventoryIssuePlan, impactList: impactList, productionRunId: productionRunId)
    }
    if (inventoryIssuePlan) {
        for (Map item : inventoryIssuePlan) {
            if (item.type == 'POOL_STOCK') {
                Map reserveResult = runService('reserveProductInventoryByFacility', [
                    workEffortId: workEffortId, facilityId: facilityId, productId: item.productId,
                    inventoryItemId: item.inventoryItemId, quantity: item.quantity, requireInventory: 'Y'
                ])
                if (ServiceUtil.isError(reserveResult)) return reserveResult
            }
        }
    }

    // Phase 2: Re-allocation (The "Steal" phase) - DO THIS SECOND
    if (impactList) {
        for (Map prospectiveRes : impactList) {
            BigDecimal stolenQty = prospectiveRes.impactQuantity instanceof String ? new BigDecimal(prospectiveRes.impactQuantity) : (prospectiveRes.impactQuantity ?: BigDecimal.ZERO)
            if (stolenQty <= 0) continue
            
            Map releaseResult = [:]
            if (prospectiveRes.impactType == 'Sales Order') {
                // CATEGORY A: Sales Order Victim -> Standard Cancellation
                // This triggers standard order-management shortfall logic.
                releaseResult = runService('cancelOrderItemShipGrpInvRes', [
                    orderId: prospectiveRes.impactId,
                    orderItemSeqId: prospectiveRes.orderItemSeqId,
                    shipGroupSeqId: prospectiveRes.shipGroupSeqId,
                    inventoryItemId: prospectiveRes.inventoryItemId,
                    cancelQuantity: stolenQty
                ])
                if (ServiceUtil.isError(releaseResult)) {
                    logError("Phase 1: Failed to release Sales Order reservation [${prospectiveRes.impactId}]: ${ServiceUtil.getErrorMessage(releaseResult)}")
                    return releaseResult
                }
            } else {
                // CATEGORY B: Work Effort / Production Task Victim -> Theft-to-Backorder Conversion
                // We increment 'quantityNotAvailable' instead of deleting the reservation.
                // This preserves the "Need" on the books while freeing the stock for current task.
                GenericValue victimRes = from('WorkEffortInvRes')
                    .where(workEffortId: prospectiveRes.impactId, inventoryItemId: prospectiveRes.inventoryItemId, productId: prospectiveRes.productId)
                    .queryOne()
                    
                if (victimRes) {
                    BigDecimal curNotAvail = victimRes.getBigDecimal('quantityNotAvailable') ?: 0.0
                    BigDecimal newNotAvail = curNotAvail.add(stolenQty)
                    
                    logInfo("Phase 1: Converting theft victim [${prospectiveRes.impactType}: ${prospectiveRes.impactId}] Item ${prospectiveRes.inventoryItemId} to backorder. QNA: ${curNotAvail} -> ${newNotAvail}")
                    
                    releaseResult = runService('updateWorkEffortInvRes', [
                        workEffortId: prospectiveRes.impactId,
                        productId: prospectiveRes.productId,
                        inventoryItemId: prospectiveRes.inventoryItemId,
                        quantityNotAvailable: newNotAvail
                    ])
                    if (ServiceUtil.isError(releaseResult)) return releaseResult
                    
                    // TRINITY SYNC: Since we manually updated the reservation status to backorder,
                    // we must restore the ATP to the item pool so Phase 2 can consume it.
                    // Guard: Ensure item exists before syncing
                    if (from("InventoryItem").where("inventoryItemId", prospectiveRes.inventoryItemId).queryOne()) {
                        runService('createInventoryItemDetail', [
                            inventoryItemId: prospectiveRes.inventoryItemId,
                            availableToPromiseDiff: stolenQty,
                            quantityOnHandDiff: 0.0,
                            workEffortId: prospectiveRes.impactId
                        ])
                    }
                    
                    // --- VICTIM SANITY GUARD START ---
                    /*
                     * This cleans up redundant backorder reservations created when an operator manually 
                     * reserved stock after the system already created negative reservations (backorders) 
                     * due to stockout during approval. The system automatically 
                     * reconciles this overlap during reallocation to maintain Trinity parity.
                     */
                    List victimPeers = from('WorkEffortInvRes')
                        .where(workEffortId: prospectiveRes.impactId, productId: prospectiveRes.productId)
                        .queryList()
                        
                    BigDecimal totalCommitted = victimPeers.sum { it.getBigDecimal('quantity') ?: 0.0 } ?: 0.0
                    
                    GenericValue victimWegs = from('WorkEffortGoodStandard')
                        .where(workEffortId: prospectiveRes.impactId, productId: prospectiveRes.productId, 
                               workEffortGoodStdTypeId: 'PRUNT_PROD_NEEDED')
                        .queryFirst()
                    BigDecimal victimEstimated = victimWegs?.getBigDecimal('estimatedQuantity') ?: 0.0
                    
                    BigDecimal excess = totalCommitted.subtract(victimEstimated)
                    if (excess > 0) {
                        logInfo("Victim Sanity Guard - Detected excess [${excess}] for victim [${prospectiveRes.impactId}] / Product [${prospectiveRes.productId}]. Estimate: ${victimEstimated}. Parrying backorders.")
                        
                        // Prioritize purging pure backorders (QOH=0 / QNA=Qty)
                        List backorders = victimPeers.findAll { (it.getBigDecimal('quantityNotAvailable') ?: 0.0) > 0 }
                            .sort { a, b -> (b.quantityNotAvailable ?: 0).compareTo(a.quantityNotAvailable ?: 0) }
                        
                        for (GenericValue backorder : backorders) {
                            if (excess <= 0) break
                            BigDecimal qna = backorder.getBigDecimal('quantityNotAvailable') ?: 0.0
                            BigDecimal qty = backorder.getBigDecimal('quantity') ?: 0.0
                            BigDecimal purgeQty = excess.min(qna).min(qty)
                            
                            if (purgeQty > 0) {
                                if (purgeQty >= qty) {
                                releaseResult = runService('releaseProductionRunTaskComponent', [
                                    workEffortId: backorder.workEffortId, 
                                    inventoryItemId: backorder.inventoryItemId, 
                                    productId: backorder.productId,
                                    quantity: qty,
                                    appendInventoryItemDetail: true // Explicitly restore ATP for the victim
                                ])
                                if (ServiceUtil.isError(releaseResult)) return releaseResult
                                excess = excess.subtract(qty)
                                } else {
                                Map releaseProductionRunTaskComponentResult = runService('releaseProductionRunTaskComponent', [
                                    workEffortId: backorder.workEffortId, 
                                    inventoryItemId: backorder.inventoryItemId, 
                                    productId: backorder.productId,
                                    quantity: purgeQty,
                                    appendInventoryItemDetail: true
                                ])
                                if (ServiceUtil.isError(releaseProductionRunTaskComponentResult)) return releaseProductionRunTaskComponentResult
                                excess = excess.subtract(purgeQty)
                                }
                            }
                        }
                    }
                    // --- VICTIM SANITY GUARD END ---

                    // Note: ATP restoration is now handled centrally by reconcileGlobalReservations 
                    // during the issuance phase to prevent double-restoration or sync issues.
                }
            }
            
            if (stolenQty > 0) {
                // Stage 2: Direct Re-reservation / Backorder Satisfaction
                // Check if we already have a reservation for this task/product/item (likely a backorder)
                GenericValue existingRes = from('WorkEffortInvRes')
                    .where(workEffortId: workEffortId, inventoryItemId: prospectiveRes.inventoryItemId, productId: prospectiveRes.productId)
                    .queryOne()
                
                if (existingRes) {
                    // Update: Promote stolen stock to available status and increment total commitment if needed
                    BigDecimal currentQty = existingRes.getBigDecimal('quantity') ?: BigDecimal.ZERO
                    BigDecimal currentNotAvail = existingRes.getBigDecimal('quantityNotAvailable') ?: BigDecimal.ZERO

                    // If we stole stock, we fulfill the backorder part first (Satisfaction)
                    // Then we add any excess to the total line amount (Promotion)
                    BigDecimal satisfactionQty = stolenQty.min(currentNotAvail)
                    BigDecimal promotionQty = stolenQty.subtract(satisfactionQty)

                    BigDecimal newQty = currentQty.add(promotionQty)
                    BigDecimal newNotAvail = currentNotAvail.subtract(satisfactionQty)

                    Map updateResResult = runService('updateWorkEffortInvRes', [
                        workEffortId: workEffortId,
                        productId: prospectiveRes.productId,
                        inventoryItemId: prospectiveRes.inventoryItemId,
                        quantity: newQty,
                        quantityNotAvailable: newNotAvail
                    ])
                    if (ServiceUtil.isError(updateResResult)) return updateResResult
                    
                    // LEDGER INTEGRITY: If we are promoting a previously "Global" backorder to a specific item,
                    // we must now deduct that debt from the item's ATP.
                    if (existingRes.inventoryItemId == null) {
                        logInfo("Phase 2: Global Debt localized to Item [${prospectiveRes.inventoryItemId}]. Deducting ${newQty} from ATP.")
                        Map localizeAtpResult = runService('createInventoryItemDetail', [
                            inventoryItemId: prospectiveRes.inventoryItemId,
                            workEffortId: workEffortId,
                            availableToPromiseDiff: -newQty,
                            quantityOnHandDiff: 0
                        ])
                        if (ServiceUtil.isError(localizeAtpResult)) return localizeAtpResult
                    }
                } else {
                    // Create: No existing reservation, create a new one
                    // reserveWorkEffortInventory ALREADY deducts ATP, so no manual sync is needed for this branch.
                    Map reserveResult = runService('reserveWorkEffortInventory', [
                        workEffortId: workEffortId, 
                        inventoryItemId: prospectiveRes.inventoryItemId,
                        productId: prospectiveRes.productId,
                        quantity: stolenQty
                    ])
                    if (ServiceUtil.isError(reserveResult)) return reserveResult
                }
            }
        }
    }

    // Phase 3: Final Issuance (Dual-List Explicit Execution)
    // We merge both lists locally to ensure that every planned item (Reserved, Pool, or Stolen) 
    // is physically issued to the task.
    List executionPlan = []
    executionPlan.addAll(inventoryIssuePlan ?: [])
    executionPlan.addAll(impactList ?: [])
    
    for (Map line : executionPlan) {
        BigDecimal qtyToIssue = line.quantity instanceof String ? new BigDecimal(line.quantity) : (line.quantity ?: (line.impactQuantity ?: BigDecimal.ZERO))
        if (qtyToIssue <= 0) continue
        
        // DELTA ISSUANCE GUARD: Skip informational UI placeholders or previously issued lines
        if (line.type == 'ALREADY_ISSUED' || line.inventoryItemId == 'ALREADY_ISSUED' || !line.inventoryItemId) {
            continue
        }

        Map issueParams = [
            workEffortId: workEffortId,
            productId: line.productId,
            inventoryItemId: line.inventoryItemId,
            quantity: qtyToIssue,
            failIfItemsAreNotAvailable: parameters.failIfItemsAreNotAvailable ?: 'Y',
            failIfItemsAreNotOnHand: parameters.failIfItemsAreNotOnHand ?: 'Y'
        ]
        
        Map issueResult = runService('executeProductionRunTaskComponentAtomic', issueParams)
        if (ServiceUtil.isError(issueResult)) {
            logError("Phase 3: Failed to issue ${qtyToIssue} of ${line.productId} from Item ${line.inventoryItemId}: ${ServiceUtil.getErrorMessage(issueResult)}")
            return issueResult
        }
    }
    // Phase 4: Purge Ghost Reservations
    // Because we skipped physically issuing from items that lacked QOH, their original 
    // reservations were bypassed by the standard issuance loop and left hovering.
    List<String> issuedProducts = executionPlan.collect { it.productId }.unique()
    for (String prodId : issuedProducts) {
        // GET: Total Requirement vs Total Issued (to determine if we are fully satisfied)
        GenericValue wegs = from('WorkEffortGoodStandard')
            .where(workEffortId: workEffortId, productId: prodId, workEffortGoodStdTypeId: 'PRUNT_PROD_NEEDED')
            .queryFirst()
        
        BigDecimal estimated = wegs?.getBigDecimal('estimatedQuantity') ?: BigDecimal.ZERO
        BigDecimal totalIssuedNow = from('WorkEffortAndInventoryAssign')
            .where('workEffortId', workEffortId, 'productId', prodId)
            .queryList().sum { it.getBigDecimal('quantity') ?: BigDecimal.ZERO } ?: BigDecimal.ZERO
        
        boolean isFullySatisfied = totalIssuedNow >= estimated
        
        // GET: All reservations we specifically issued against during this transaction
        List<String> explicitlySatisfiedItems = executionPlan.findAll { it.productId == prodId && it.inventoryItemId != 'ALREADY_ISSUED' }.collect { it.inventoryItemId }

        List ghosts = from('WorkEffortInvRes').where(workEffortId: workEffortId, productId: prodId).queryList()
        if (ghosts) {
            for (GenericValue ghost : ghosts) {
                // RULE: Purge if either:
                // 1. The entire product requirement is now met (Fulfillment Guard)
                // 2. The specific item was satisfied AND its remaining reservation quantity is now zero (Satisfaction Guard)
                BigDecimal currentResQty = ghost.getBigDecimal('quantity') ?: BigDecimal.ZERO
                if (isFullySatisfied || (explicitlySatisfiedItems.contains(ghost.inventoryItemId) && currentResQty <= 0)) {
                    releaseResult = runService('releaseProductionRunTaskComponent', [
                        workEffortId: ghost.workEffortId,
                        productId: ghost.productId,
                        inventoryItemId: ghost.inventoryItemId,
                        quantity: ghost.getBigDecimal('quantity'),
                        appendInventoryItemDetail: true // MUST restore ATP when purging logical debt
                    ])
                    if (ServiceUtil.isError(releaseResult)) return releaseResult
                }
            }
        }
    }
    
    return success(inventoryIssuePlan: inventoryIssuePlan, impactList: impactList, productionRunId: productionRunId)
}

/**
 * Balances manufacturing reservations by re-reserving backordered items against pool stock.
 * Following the standard OFBiz pattern for order reassignment, this service:
 * 1. Identifies all WorkEffortInvRes records with quantityNotAvailable > 0.
 * 2. Cancels the existing reservation (restoring any physical portion to ATP).
 * 3. Re-reserves the requirement using the core engine, which will prioritize physical stock.
 */
Map reassignWorkEffortInventoryReservations() {
    String productId = parameters.productId
    String facilityId = parameters.facilityId
    String inventoryItemId = parameters.inventoryItemId
    if ((!productId || !facilityId) && inventoryItemId) {
        GenericValue inventoryItem = from('InventoryItem').where('inventoryItemId', inventoryItemId).queryOne()
        productId = productId ?: inventoryItem?.productId
        facilityId = facilityId ?: inventoryItem?.facilityId
    }
    if (!productId || !facilityId) return success(satisfiedCount: 0)

    Set eligibleWorkEffortIds = parameters.workEffortIds ? new HashSet(parameters.workEffortIds) : null
    List activeProductionRunStatuses = ["PRUN_SCHEDULED", "PRUN_DOC_PRINTED", "PRUN_RUNNING"]

    // Find all backordered reservations for this product
    List backorders = from("WorkEffortInvRes")
        .where(EntityCondition.makeCondition("productId", EntityOperator.EQUALS, productId),
               EntityCondition.makeCondition("quantityNotAvailable", EntityOperator.GREATER_THAN, BigDecimal.ZERO))
        .queryList()
        .findAll { res ->
            // Filter by facility (Traverse up to find the root Production Run's facility)
            GenericValue task = from("WorkEffort").where("workEffortId", res.workEffortId).cache().queryOne()
            String taskFacilityId = null
            String statusId = null
            GenericValue current = task
            while (current) {
                if (!taskFacilityId && current.facilityId) {
                    taskFacilityId = current.facilityId
                }
                if (!statusId && current.workEffortTypeId == 'PROD_ORDER_HEADER') {
                    statusId = current.currentStatusId
                }
                current = current.workEffortParentId ? from("WorkEffort").where("workEffortId", current.workEffortParentId).cache().queryOne() : null
            }
            return taskFacilityId == facilityId &&
                   statusId in activeProductionRunStatuses &&
                   (!eligibleWorkEffortIds || eligibleWorkEffortIds.contains(res.workEffortId))
        }

    if (!backorders) return success()

    // FIFO Sort: Settle oldest backorders first
    backorders.sort { it.createdStamp }

    int satisfiedCount = 0
    for (GenericValue res : backorders) {
        BigDecimal qty = res.getBigDecimal("quantity") ?: BigDecimal.ZERO
        BigDecimal originalQna = res.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO
        
        // 1. Release the whole reservation (Debt + Physical)
        // appendInventoryItemDetail: true ensures that if there was any physical backing, it's returned to the pool
        // so it can be re-allocated fairly by the core engine.
        Map releaseResult = runService('releaseProductionRunTaskComponent', [
            workEffortId: res.workEffortId,
            productId: res.productId,
            inventoryItemId: res.inventoryItemId,
            quantity: qty,
            appendInventoryItemDetail: true
        ])
        if (ServiceUtil.isError(releaseResult)) return releaseResult

        // 2. Re-reserve using the core engine
        // requireInventory: 'N' allows the engine to create a new backorder if stock is still insufficient,
        // but it will pick pool stock first if available.
        Map reserveResult = runService('reserveWorkEffortInventoryItem', [
            workEffortId: res.workEffortId,
            productId: res.productId,
            facilityId: facilityId,
            quantity: qty,
            requireInventory: 'N'
        ])
        if (ServiceUtil.isError(reserveResult)) return reserveResult

        List updatedReservations = from("WorkEffortInvRes")
            .where("workEffortId", res.workEffortId, "productId", res.productId)
            .queryList()
        BigDecimal updatedQna = updatedReservations.inject(BigDecimal.ZERO) { sum, updatedRes ->
            sum + (updatedRes.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO)
        }
        if (updatedQna < originalQna) {
            satisfiedCount++
        }
    }

    return success(satisfiedCount: satisfiedCount)
}

/**
 * Nightly job to reconcile manufacturing inventory (Optimized Performance Version).
 * 1. Data-Driven Entry: Targets only records with logical debt (shortfall).
 * 2. ATP Caching: Groups lookups by Product/Facility to minimize DB round-trips.
 * 3. The Auditor: Purges phantom backorders for data integrity.
 * 4. The Satisfier: Settles legitimate backorders using Pool ATP.
 */
Map reconcileInventoryForProductionJobs() {
    // Phase 1: Identify all logical debt records (backorders) in the system
    List backorders = from("WorkEffortInvRes")
        .where(EntityCondition.makeCondition("quantityNotAvailable", EntityOperator.GREATER_THAN, BigDecimal.ZERO))
        .queryList()
        
    if (!backorders) return success("No backorders found.")

    logInfo("RECON: Found ${backorders.size()} total backorder records. System-wide logical debt detected.")

    // Phase 2: Filter by active Production Run status and group by Facility/Product
    Map groupedBackorders = [:]
    Map workEffortCache = [:]
    
    for (GenericValue res : backorders) {
        String weId = res.workEffortId
        if (!workEffortCache.containsKey(weId)) {
            // Traverse up to find the root Production Run and its Facility
            GenericValue task = from("WorkEffort").where("workEffortId", weId).queryOne()
            String facilityId = null
            String statusId = null
            
            GenericValue current = task
            while (current) {
                if (!facilityId) facilityId = current.facilityId
                if (!statusId && current.workEffortTypeId == 'PROD_ORDER_HEADER') {
                    statusId = current.currentStatusId
                }
                if (current.workEffortParentId) {
                    current = from("WorkEffort").where("workEffortId", current.workEffortParentId).cache().queryOne()
                } else {
                    break
                }
            }
            workEffortCache[weId] = [facilityId: facilityId, statusId: statusId]
        }
        
        Map context = workEffortCache[weId]
        // Only process if it belongs to an active PRUN (Scheduled, Printed, or Running)
        if (context.statusId in ["PRUN_SCHEDULED", "PRUN_DOC_PRINTED", "PRUN_RUNNING"] && context.facilityId) {
            String key = "${context.facilityId}|${res.productId}"
            if (!groupedBackorders.containsKey(key)) groupedBackorders[key] = []
            groupedBackorders[key].add(res)
        }
    }
    logInfo("RECON: Grouped into ${groupedBackorders.size()} batches.")

    int totalPurged = 0
    int totalSatisfied = 0
    Map facilityPolicyCache = [:]

    // Phase 3: Process groups using ATP Caching
    groupedBackorders.each { key, members ->
        // FIFO: Sort backorders by createdStamp (oldest first)
        members.sort { it.createdStamp }
        String[] parts = key.split("\\|")
        String facilityId = parts[0]
        String productId = parts[1]
        
        // TRINITY SYNC: Ensure candidate pool is logically consistent before analysis.
        // ROLE: This service acts as a "Ledger Flush" or "Baseline Sync". It force-recalculates
        // ATP to ensure that recent physical issuances, thefts, or manual overrides are 
        // fully synchronized with the logical pool. This prevents "ATP Drift" and 
        // ensure the Auditor/Satisfier operates on the absolute truth of physical reality.
        from('InventoryItem').where(productId: productId, facilityId: facilityId).queryList().each {
            runService("trinitySync", [inventoryItemId: it.inventoryItemId])
        }

        // Fetch Facility Policies (Cached per group)
        if (!facilityPolicyCache.containsKey(facilityId)) {
            facilityPolicyCache[facilityId] = from("Facility").where("facilityId", facilityId).cache().queryOne()
        }
        GenericValue facility = facilityPolicyCache[facilityId]
        String allowTheft = facility?.allowInventoryTheft ?: 'N'
        String enableRecon = facility?.reconcilePrunBackorders ?: 'N'
        
        // Fetch Pool ATP (Cached for all members of this Product/Facility group)
        BigDecimal availableATP = BigDecimal.ZERO
        if (enableRecon == 'Y') {
            Map invResult = runService("getInventoryAvailableByFacility", [productId: productId, facilityId: facilityId])
            availableATP = invResult.availableToPromiseTotal ?: BigDecimal.ZERO
        }
        
        // Track how much we've already purged for each task in this group to avoid over-purging 
        // when multiple reservation records exist for the same task/product.
        Map purgedPerTask = [:]

        // --- PHASE 3.1: THE AUDITOR (Sanity Check) - LIFO Priority ---
        // Goal: Eliminate newest "phantom" debt that exceeds legitimate requirements.
        members.reverse().each { res ->
            String taskId = res.workEffortId
            
            // We use a "Total Life Commitment" check: Issued + Reserved + QNA.
            Map qtyResult = runService("getTaskQuantities", [workEffortId: taskId, productId: productId])
            BigDecimal estimated = qtyResult.requiredQuantity ?: 0
            BigDecimal issued = qtyResult.issuedQuantity ?: 0
            BigDecimal reserved = qtyResult.reservedQuantity ?: 0
            
            // POLICY: reconciliation-enabled facilities audit against remaining demand.
            // Traditional facilities keep the broader estimate ceiling to avoid changing old behavior.
            BigDecimal commitmentCeiling = (enableRecon == 'Y') ? (estimated - issued).max(BigDecimal.ZERO) : estimated
            BigDecimal totalCommitment = issued + reserved
            boolean isExcess = totalCommitment > estimated
            if (isExcess) {
                // Account for what we've already purged for this task in this group loop
                BigDecimal alreadyPurged = purgedPerTask[taskId] ?: BigDecimal.ZERO
                BigDecimal totalExcess = (reserved - commitmentCeiling).max(BigDecimal.ZERO)
                BigDecimal remainingToPurge = (totalExcess - alreadyPurged).max(BigDecimal.ZERO)
                
                BigDecimal resQna = res.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO
                BigDecimal purgeAmount = remainingToPurge.min(resQna)
                
                if (purgeAmount > 0) {
                    logInfo("AUDITOR: Purging ${purgeAmount} phantom backorders for Task [${taskId}] Product [${productId}]. Mode: ${allowTheft}")
                    
                    // Standard Release (Satisfied/Purged pattern): 
                    // appendInventoryItemDetail: false because logical debt doesn't restore ATP.
                    Map releaseResult = runService('releaseProductionRunTaskComponent', [
                        workEffortId: taskId,
                        productId: productId,
                        inventoryItemId: res.inventoryItemId,
                        quantity: purgeAmount,
                        appendInventoryItemDetail: false
                    ])
                    if (ServiceUtil.isError(releaseResult)) return releaseResult
                    
                    alreadyPurged += purgeAmount
                    totalPurged += purgeAmount.intValue()
                    purgedPerTask[taskId] = alreadyPurged
                }
            }
        }

        // --- PHASE 3.2: THE SATISFIER (Auto-healing) - FIFO Priority ---
        // Goal: Settle legitimate backorders using newly available physical stock in the facility pool.
        // Offload satisfaction and reassignment to native ledger-compliant inventory services.
        if (enableRecon == 'Y') {
            Map reassignResult = runService('reassignWorkEffortInventoryReservations', [
                productId: productId,
                facilityId: facilityId,
                workEffortIds: members*.workEffortId.unique()
            ])
            if (ServiceUtil.isError(reassignResult)) return reassignResult
            totalSatisfied += (reassignResult.satisfiedCount ?: 0)
        }
    }
    
    String msg = "Nightly reconciliation completed. Analyzed ${groupedBackorders.size()} unique Product/Facility pairs. Purged: ${totalPurged}, Satisfied: ${totalSatisfied}"
    logInfo(msg)
    return success(msg)
}
/**
 * Condition service for SECA: Checks if the facility associated with an inventory item
 * has real-time backorder reconciliation enabled for production runs.
 */
def checkProductionRunFacilityRecon() {
    String inventoryItemId = parameters.inventoryItemId ?: parameters.serviceContext?.inventoryItemId
    GenericValue inventoryItem = from("InventoryItem").where("inventoryItemId", inventoryItemId).queryOne()
    
    if (inventoryItem && inventoryItem.facilityId) {
        GenericValue facility = from("Facility").where("facilityId", inventoryItem.facilityId).cache().queryOne()
        if (facility && facility.reconcilePrunBackorders == 'Y') {
            return success([conditionReply: true, productId: inventoryItem.productId, facilityId: inventoryItem.facilityId])
        }
    }
    return success(conditionReply: false)
}
