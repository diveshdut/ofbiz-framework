package org.apache.ofbiz.manufacturing.test

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase
import java.sql.Timestamp
import java.math.BigDecimal
import java.math.RoundingMode
import org.apache.ofbiz.base.util.Debug
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilMisc

class InventoryIssuanceTests extends OFBizTestCase {

    InventoryIssuanceTests(String name) {
        super(name)
    }

    protected GenericValue userLogin

    @Override
    protected void setUp() throws Exception {
        super.setUp()
        userLogin = from("UserLogin").where("userLoginId", "system").queryOne()
        
        // Target Purge: Clean up only the items and reservations we own.
        delegator.removeByCondition("WorkEffortInvRes", EntityCondition.makeCondition("productId", EntityOperator.LIKE, "II_%"))
        delegator.removeByCondition("WorkEffortInventoryAssign", EntityCondition.makeCondition("inventoryItemId", EntityOperator.LIKE, "II_%"))
        delegator.removeByCondition("InventoryItemDetail", EntityCondition.makeCondition("inventoryItemId", EntityOperator.LIKE, "II_%"))
        delegator.removeByCondition("InventoryItem", EntityCondition.makeCondition("inventoryItemId", EntityOperator.LIKE, "II_%"))
        
        ensureFacilityPolicies()
        delegator.clearAllCaches()
        Debug.logInfo("InventoryIssuanceTests: Environment purged for II_% items.", "InventoryIssuanceTests")
    }

    /**
     * Helper to safely upsert a Facility record.
     * Fixes: delegator.createOrStore(String, Map) signature does not exist in OFBiz API.
     */
    void createOrStoreFacility(Map fields) {
        GenericValue gv = delegator.makeValue("Facility", fields)
        delegator.createOrStore(gv)
    }

    void ensureFacilityPolicies() {
        createOrStoreFacility([facilityId: "II_WH", facilityName: "Test Warehouse", facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        createOrStoreFacility([facilityId: "WH_FLUID", facilityName: "Fluid Warehouse", facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "Y"])
        createOrStoreFacility([facilityId: "WH_TRADITIONAL", facilityName: "Traditional Warehouse", facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "N", reconcilePrunBackorders: "N"])
        createOrStoreFacility([facilityId: "WH_SAFE", facilityName: "Safe Recovery Warehouse", facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "N", reconcilePrunBackorders: "Y"])
        createOrStoreFacility([facilityId: "WH_NO_AUTO_RES", facilityName: "No Auto Reservation Warehouse",
            facilityTypeId: "WAREHOUSE", ownerPartyId: "II_COMPANY", autoReservePrun: "N",
            allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
    }

    /**
     * The "Trinity of Truth" Assertion Engine.
     * Verifies Physical Stock, Reservations, and Ledger in one pass.
     */
    void assertTrinityOfTruth(String productId, String workEffortId, BigDecimal expectedIssued, BigDecimal expectedRes, BigDecimal expectedAtp = null, String facilityId = "II_WH") {
        // 1. Verify WorkEffortInventoryAssign (Physical Issuance)
        def assignments = from("WorkEffortInventoryAssign")
                .where("workEffortId", workEffortId)
                .queryList()
        BigDecimal totalIssued = assignments.inject(BigDecimal.ZERO) { sum, item -> 
            def inventoryItem = from("InventoryItem").where("inventoryItemId", item.inventoryItemId).queryOne()
            if (inventoryItem && inventoryItem.productId == productId) {
                return sum + (item.getBigDecimal("quantity") ?: BigDecimal.ZERO)
            }
            return sum
        }
        assertEquals("Physical Issuance Mismatch for ${productId}", expectedIssued.setScale(2, RoundingMode.HALF_UP), totalIssued.setScale(2, RoundingMode.HALF_UP))

        // 2. Verify WorkEffortInvRes (Current Requirement/Reservation)
        def resRecords = from("WorkEffortInvRes")
                .where("workEffortId", workEffortId, "productId", productId)
                .queryList()
        BigDecimal totalRes = resRecords.inject(BigDecimal.ZERO) { sum, res -> 
            BigDecimal qty = res.getBigDecimal("quantity") ?: BigDecimal.ZERO
            BigDecimal qna = res.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO
            return sum + (qty - qna)
        }
        assertEquals("Net Reservation (Physical) Mismatch for ${productId}", expectedRes.setScale(2, RoundingMode.HALF_UP), totalRes.setScale(2, RoundingMode.HALF_UP))

        // 3. Verify Inventory Health (ATP, QOH, Accounting)
        def inventoryItems = from("InventoryItem").where("productId", productId, "facilityId", facilityId).cache(false).queryList()
        BigDecimal totalAtp = inventoryItems.inject(BigDecimal.ZERO) { sum, item -> sum + (item.getBigDecimal("availableToPromiseTotal") ?: BigDecimal.ZERO) }
        BigDecimal totalQoh = inventoryItems.inject(BigDecimal.ZERO) { sum, item -> sum + (item.getBigDecimal("quantityOnHandTotal") ?: BigDecimal.ZERO) }
        BigDecimal totalAccounting = inventoryItems.inject(BigDecimal.ZERO) { sum, item -> sum + (item.getBigDecimal("accountingQuantityTotal") ?: BigDecimal.ZERO) }

        // Truth 2: Available To Promise (ATP) parity
        // Sum ATP from all items MINUS consider any "Global" backorders (item null) that haven't been localized yet.
        // Note: WorkEffortInvRes has no facilityId field; null inventoryItemId means a global (unlocalized) backorder.
        BigDecimal globalDebt = from("WorkEffortInvRes").where("productId", productId, "inventoryItemId", null).queryList()
                .inject(BigDecimal.ZERO) { sum, it -> 
                    BigDecimal qty = it.getBigDecimal("quantity") ?: 0
                    BigDecimal qna = it.getBigDecimal("quantityNotAvailable") ?: 0
                    return sum + (qty - qna) 
                }
        
        BigDecimal trueTotalAtp = totalAtp - globalDebt
        
        if (expectedAtp != null) {
            assertEquals("ATP Global Pool Mismatch for ${productId}", expectedAtp.setScale(2, RoundingMode.HALF_UP), trueTotalAtp.setScale(2, RoundingMode.HALF_UP))
        }
        
        assertEquals("Ledger Sync (Accounting vs QOH) Mismatch for ${productId}", totalQoh.setScale(2, RoundingMode.HALF_UP), totalAccounting.setScale(2, RoundingMode.HALF_UP))

        // 4. Verify WorkEffortGoodStandard Satisfaction status
        def wegs = from("WorkEffortGoodStandard")
                .where("workEffortId", workEffortId, "productId", productId, "workEffortGoodStdTypeId", "PRUNT_PROD_NEEDED")
                .queryFirst()
        if (wegs) {
            BigDecimal needed = wegs.getBigDecimal("estimatedQuantity") ?: BigDecimal.ZERO
            if (totalIssued >= needed && needed > 0) {
                assertEquals("Logical Satisfaction Failure: WorkEffortGoodStandard should be WEGS_COMPLETED for fully issued ${productId}", "WEGS_COMPLETED", wegs.statusId)
            } else if (totalIssued > 0) {
                assertNotSame("Logical Satisfaction Failure: WorkEffortGoodStandard should NOT be WEGS_COMPLETED for partially issued ${productId}", "WEGS_COMPLETED", wegs.statusId)
            }
        }
    }

    /** Helper for standard PR setup */
    String setUpProductionRun(String productId, BigDecimal quantity, String facilityId = "II_WH") {
        if (!userLogin) {
            userLogin = from("UserLogin").where("userLoginId", "system").queryOne()
        }
        assert userLogin != null : "UserLogin 'system' must exist for tests"
        Map createResult = dispatcher.runSync("createProductionRun", [
                productId: productId, pRQuantity: quantity,
                startDate: UtilDateTime.nowTimestamp(),
                facilityId: facilityId, userLogin: userLogin
        ])
        String productionRunId = createResult.productionRunId
        
        // Confirm & Reserve
        Map statusResult = dispatcher.runSync("changeProductionRunStatus", [
                productionRunId: productionRunId, statusId: "PRUN_DOC_PRINTED", 
                userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(statusResult)

        // Return the first task ID (usually where issuance happens)
        List tasks = from("WorkEffort").where("workEffortParentId", productionRunId).orderBy("workEffortId").queryList()
        return tasks ? tasks[0].workEffortId : productionRunId
    }

    void setUpInventory(String facilityId, String productId, String itemId, BigDecimal quantity, boolean purgeExisting = true) {
        // Authorization Guard
        if (!userLogin) {
            userLogin = from("UserLogin").where("userLoginId", "system").queryOne()
        }

        // Purge state
        // Smart Purge: Only clear state if the item does NOT exist yet.
        // This prevents subsequent stock additions from wiping out existing test reservations.
        if (purgeExisting && !from("InventoryItem").where("inventoryItemId", itemId).queryOne()) {
            List itemIds = from("InventoryItem").where("productId", productId, "facilityId", facilityId).queryList().inventoryItemId
            if (itemIds) {
                delegator.removeByCondition("InventoryItemDetail", EntityCondition.makeCondition("inventoryItemId", EntityOperator.IN, itemIds))
                delegator.removeByCondition("WorkEffortInventoryAssign", EntityCondition.makeCondition("inventoryItemId", EntityOperator.IN, itemIds))
                delegator.removeByCondition("WorkEffortInvRes", EntityCondition.makeCondition("inventoryItemId", EntityOperator.IN, itemIds))
                delegator.removeByCondition("InventoryItem", EntityCondition.makeCondition("inventoryItemId", EntityOperator.IN, itemIds))
            }
            // Also remove any product-level reservations that might not be tied to an item yet (global pool)
            delegator.removeByCondition("WorkEffortInvRes", EntityCondition.makeCondition("productId", productId))
        }

        // Create via delegator for immediate visibility if not exists
        if (!from("InventoryItem").where("inventoryItemId", itemId).queryOne()) {
            delegator.create("InventoryItem", [
                inventoryItemId: itemId, productId: productId, facilityId: facilityId,
                inventoryItemTypeId: "NON_SERIAL_INV_ITEM", ownerPartyId: "II_COMPANY",
                quantityOnHandTotal: 0.0, availableToPromiseTotal: 0.0, accountingQuantityTotal: 0.0,
                datetimeReceived: UtilDateTime.nowTimestamp()
            ])
        }

        if (quantity != 0) {
            delegator.create("InventoryItemDetail", [
                inventoryItemDetailSeqId: delegator.getNextSeqId("InventoryItemDetail"),
                inventoryItemId: itemId, 
                availableToPromiseDiff: quantity, 
                quantityOnHandDiff: quantity,
                accountingQuantityDiff: quantity,
                effectiveDate: UtilDateTime.nowTimestamp()
            ])
        }
        
        delegator.clearAllCaches()
        
        // TRINITY SYNC: Ensure setup state is consistent before test starts
        dispatcher.runSync("reconcileGlobalReservations", [inventoryItemId: itemId, amountToIssue: 0.0, userLogin: userLogin])
    }

    String setUpNoAutoReserveProductionRun(String matAItemId, BigDecimal matAQuantity) {
        createOrStoreFacility([facilityId: "WH_NO_AUTO_RES", facilityName: "No Auto Reservation Warehouse",
            facilityTypeId: "WAREHOUSE", ownerPartyId: "II_COMPANY", autoReservePrun: "N",
            allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory("WH_NO_AUTO_RES", "II_MAT_A_COST", matAItemId, matAQuantity)
        setUpInventory("WH_NO_AUTO_RES", "II_MAT_C_COST", "${matAItemId}_C", 100.0)
        return setUpProductionRun("II_PROD_MANUF", 10.0, "WH_NO_AUTO_RES")
    }

    // --- Category 1 & 2: Basic & Force Issuance ---

    void testS1_1_StrictIssuanceFailure() {
        String facId = "WH_S1_1"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S1_1", 100.0)
        // Request PR for 60 units (Needs 120 MAT_A).
        String weId = setUpProductionRun("II_PROD_MANUF", 60.0, facId)

        // Run in a new transaction so an error-return does NOT poison the outer tx.
        Map result = dispatcher.runSync("issueProductionRunTask", [
                workEffortId: weId, failIfItemsAreNotAvailable: "Y", userLogin: userLogin
        ], 0, true)
        assert ServiceUtil.isError(result)
    }

    void testS1_2_ForceWithTheft() {
        String facId = "WH_S1_2"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S1_2", 100.0)
        setUpInventory(facId, "II_MAT_C_COST", "II_MAT_C_S1_2", 100.0)
        // Setup Victim: Needs 50 PR -> 100 MAT_A Reserved. (Pool = 100, so Victim gets all).
        String victimWeId = setUpProductionRun("II_PROD_MANUF", 50.0, facId)
        assertTrinityOfTruth("II_MAT_A_COST", victimWeId, 0.0, 100.0, 0.0, facId)

        // Setup Thief: Needs 25 PR -> 50 MAT_A. Pool is empty!
        String thiefWeId = setUpProductionRun("II_PROD_MANUF", 25.0, facId)
        
        // Action: Force Issue Thief. Should steal 50 from Victim.
        Map result = dispatcher.runSync("issueProductionRunTask", [
                workEffortId: thiefWeId, failIfItemsAreNotAvailable: "N", userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(result)

        // Verify Trinity: Thief has 50 issued. Victim has 50 issued, 50 reserved, 0 ATP.
        // ATP is 0 because the 50 remaining reserved units are backordered (QNA=50).
        assertTrinityOfTruth("II_MAT_A_COST", thiefWeId, 50.0, 0.0, 0.0, facId)
        assertTrinityOfTruth("II_MAT_A_COST", victimWeId, 0.0, 50.0, 0.0, facId)
        
        // Verify Victim's Backorder
        def victimRes = from("WorkEffortInvRes").where("workEffortId", victimWeId, "productId", "II_MAT_A_COST").queryFirst()
        assertEquals("Victim should be backordered by 50", 50.0, victimRes.getBigDecimal("quantityNotAvailable").doubleValue(), 0.001)
    }

    // --- Category 4: Real-time Reconciliation (SECA) ---

    /**
     * Test Case: Real-time Reconciliation on Inventory Receipt.
     * Verifies that receiving inventory for a backordered product triggers the SECA
     * and satisfies the pending Production Run reservation.
     */
    void testS4_1_RealTimeReconciliationOnReceipt() {
        String facId = "WH_RECON_Y"
        // 1. Setup Facility with reconciliation enabled
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "Y"])
        
        // 2. Setup empty inventory for II_MAT_C_COST to ensure a backorder is created
        // We initialize it with 0 to have the record ready but empty.
        setUpInventory(facId, "II_MAT_C_COST", "II_MAT_C_RECON", 0.0)
        
        // 3. Create Production Run that needs 20 II_MAT_C_COST.
        // It will be backordered because there is no stock.
        String weId = setUpProductionRun("II_PROD_MANUF", 20.0, facId)
        
        // 4. Verify initial backorder state
        def res = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_C_COST").queryFirst()
        assertNotNull("Reservation should exist", res)
        assertEquals("Should be 20 backordered initially", 20.0, (res.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO).doubleValue(), 0.001)
        assertNotNull("Backorder should be anchored to a logical inventory item", res.inventoryItemId)

        // 5. Action: Simulate receipt at inventory level and invoke the same balancing hook.
        // receiveInventoryProduct also performs accounting, which this focused inventory test does not set up.
        String newItemId = "II_MAT_C_RECON_RCPT"
        delegator.create("InventoryItem", [
                inventoryItemId: newItemId, productId: "II_MAT_C_COST", facilityId: facId,
                inventoryItemTypeId: "NON_SERIAL_INV_ITEM", ownerPartyId: "II_COMPANY",
                quantityOnHandTotal: 0.0, availableToPromiseTotal: 0.0, accountingQuantityTotal: 0.0,
                datetimeReceived: UtilDateTime.nowTimestamp()
        ])
        dispatcher.runSync("createInventoryItemDetail", [
                inventoryItemId: newItemId,
                availableToPromiseDiff: 50.0,
                quantityOnHandDiff: 50.0,
                accountingQuantityDiff: 50.0,
                userLogin: userLogin
        ])
        Map balanceResult = dispatcher.runSync("balanceInventoryItems", [inventoryItemId: newItemId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(balanceResult)
        
        // 6. Assert: The reservation should now be satisfied and localized to the new item.
        // Trinity check: 0 issued, 20 reserved, 30 ATP (50 total received - 20 reserved)
        assertTrinityOfTruth("II_MAT_C_COST", weId, 0.0, 20.0, 30.0, facId)
        
        // Double check the reservation record specifically
        res = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_C_COST").queryFirst()
        assertEquals("Reservation should now be fully available (QNA=0)", 0.0, (res.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO).doubleValue(), 0.001)
        assertEquals("Reservation should be tied to the newly received item", newItemId, res.inventoryItemId)
    }

    /**
     * Scenario: Production Chain Auto-Satisfaction
     * 1. PR-A (Consumer) needs Product D.
     * 2. Product D is out of stock -> PR-A creates a backorder.
     * 3. PR-B (Producer) produces Product D.
     * 4. Completion of PR-B triggers inventory receipt of Product D.
     * 5. The receipt triggers balanceInventoryItems (via SECA).
     * 6. PR-A's backorder for Product D is automatically satisfied.
     */
    void testS4_2_ProductionChainAutoSatisfaction() {
        String facId = "WH_CHAIN_RECON"
        // 1. Setup Facility with reconciliation enabled
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "Y"])
        
        // 2. Setup initial stock for RAW material
        setUpInventory(facId, "II_MAT_E_RAW", "II_MAT_E_RAW_S4_2", 100.0)
        
        // Ensure NO stock for intermediate product
        setUpInventory(facId, "II_MAT_D_PRODUCED", "II_MAT_D_RECON", 0.0)

        // 3. PR-A (Consumer): Needs Produced Material D
        // II_PROD_CHAIN needs 1 II_MAT_D_PRODUCED
        String prA_TaskId = setUpProductionRun("II_PROD_CHAIN", 10.0, facId)
        
        // Verify PR-A is backordered for II_MAT_D_PRODUCED
        def resA = from("WorkEffortInvRes").where("workEffortId", prA_TaskId, "productId", "II_MAT_D_PRODUCED").queryFirst()
        assertNotNull("Reservation for PR-A should exist", resA)
        assertEquals("PR-A should be backordered for 10 units", 10.0, (resA.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO).doubleValue(), 0.001)

        // 4. PR-B (Producer): Produces Material D
        // II_MAT_D_PRODUCED needs 1 II_MAT_E_RAW
        String prB_TaskId = setUpProductionRun("II_MAT_D_PRODUCED", 15.0, facId)
        GenericValue taskB = from("WorkEffort").where("workEffortId", prB_TaskId).queryOne()
        String prB_Id = taskB.workEffortParentId

        // Move PR-B Task to RUNNING
        dispatcher.runSync("changeProductionRunTaskStatus", [
            productionRunId: prB_Id, workEffortId: prB_TaskId, 
            statusId: "PRUN_RUNNING", userLogin: userLogin
        ])
        
        // PR-B should have reserved II_MAT_E_RAW (since we have 100 in stock)
        def resB = from("WorkEffortInvRes").where("workEffortId", prB_TaskId, "productId", "II_MAT_E_RAW").queryFirst()
        assertNotNull("Reservation for PR-B should exist", resB)
        
        // 5. Action: Physical Issuance for PR-B
        // This consumes II_MAT_E_RAW
        Map issueResult = dispatcher.runSync("issueProductionRunTask", [
            workEffortId: prB_TaskId, userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(issueResult)

        // 6. Action: Complete the Task
        // Mark the task as finished before officially receiving the goods
        dispatcher.runSync("changeProductionRunTaskStatus", [
            productionRunId: prB_Id, workEffortId: prB_TaskId, 
            statusId: "PRUN_COMPLETED", userLogin: userLogin
        ])

        // 7. Action: Produce Material D in PR-B (Declaration/Receipt)
        // We use productionRunProduce to receive the 15 units of II_MAT_D_PRODUCED
        Map produceResult = dispatcher.runSync("productionRunProduce", [
            workEffortId: prB_Id, 
            quantity: 15.0,
            userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(produceResult)
        String newInventoryItemId = ((List) produceResult.inventoryItemIds)[0]

        // 8. Assert: Auto-Satisfaction!
        // The receipt of 15 units of II_MAT_D_PRODUCED should have triggered balanceInventoryItems,
        // which should have satisfied PR-A's backorder.
        
        // Check PR-A's reservation again
        resA = from("WorkEffortInvRes").where("workEffortId", prA_TaskId, "productId", "II_MAT_D_PRODUCED").queryFirst()
        assertEquals("PR-A's reservation should now be satisfied (QNA=0)", 0.0, (resA.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO).doubleValue(), 0.001)
        assertEquals("PR-A's reservation should be tied to the item produced by PR-B", newInventoryItemId, resA.inventoryItemId)
        
        // Verify Trinity for PR-A
        // 0 issued, 10 reserved, 5 ATP (15 produced - 10 reserved)
        assertTrinityOfTruth("II_MAT_D_PRODUCED", prA_TaskId, 0.0, 10.0, 5.0, facId)
    }

    // --- Category 3: Backorders & Residuals ---

    void testS3_2_NegativeReservationResolution() {
        String facId = "WH_S3_2"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        
        // 1. Setup: 0 Stock for II_MAT_C_COST, but enough Material A
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S3_2", 100.0)
        setUpInventory(facId, "II_MAT_C_COST", "II_MAT_C_S3_2", 0.0) // Initialize empty item for C
        // Use structured PR (needs 1 II_MAT_C_COST)
        String weId = setUpProductionRun("II_PROD_MANUF", 20.0, facId) // Needs 20 MAT_C and 40 MAT_A
        
        // Assert: 20 backordered.
        def res = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_C_COST").queryFirst()
        assertEquals("Initial state should be 20 backordered", 20.0, (res.getBigDecimal("quantityNotAvailable") ?: BigDecimal.ZERO).doubleValue(), 0.001)
        
        // 2. Action: Receive Stock (Simulate receipt by adding detail to avoid heavy accounting service)
        dispatcher.runSync("createInventoryItemDetail", [
                inventoryItemId: "II_MAT_C_S3_2",
                quantityOnHandDiff: 50.0, 
                availableToPromiseDiff: 50.0,
                accountingQuantityDiff: 50.0,
                userLogin: userLogin
        ])
        
        // 3. Action: Force Issue
        // The issuance engine should see the real stock and purge the stale backorder record.
        Map result = dispatcher.runSync("issueProductionRunTask", [
                workEffortId: weId, failIfItemsAreNotAvailable: "N", userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(result)
        
        // 4. Assert: Backorder PURGED. Real stock issued.
        if (!weId) { Debug.logInfo("testS3_2 FAILED: weId is null", "InventoryIssuanceTests") }
        assertTrinityOfTruth("II_MAT_C_COST", weId, 20.0, 0.0, 30.0, facId)
    }

    void testS3_4_ImpactPlanAudit() {
        String facId = "WH_S3_4"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S3_4", 100.0)
        setUpInventory(facId, "II_MAT_C_COST", "II_MAT_C_S3_4", 100.0)
        
        // 1. Setup: Pool has 100 MAT_A.
        // Victim 1 Needs 25.
        // Victim 2 Needs 25. 
        // Other Task Needs 50 (to empty pool).
        
        String v1 = setUpProductionRun("II_PROD_MANUF", 12.5, facId) // Needs 25 MAT_A
        String v2 = setUpProductionRun("II_PROD_MANUF", 12.5, facId) // Needs 25 MAT_A
        String o3 = setUpProductionRun("II_PROD_MANUF", 25.0, facId) // Needs 50 MAT_A (deplete pool)
        
        // 2. Setup Thief: Needs 15 MAT_A. 
        String t4 = setUpProductionRun("II_PROD_MANUF", 7.5, facId) 
        
        // 2. Action: Audit Impact (The "Plan")
        Map impactResult = dispatcher.runSync("getProductionRunTaskForceIssueImpact", [
            workEffortId: t4, facilityId: facId, productId: "II_MAT_A_COST", userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(impactResult)
        
        List impactList = (List) impactResult.impactList
        List issuePlan = (List) impactResult.inventoryIssuePlan
        
        // Assert: Impact List holds the theft (50 units stolen from victim)
        BigDecimal totalImpact = impactList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.quantity ?: BigDecimal.ZERO) }
        assertEquals("Thief should steal 15 units", 15.0, totalImpact.doubleValue(), 0.001)
        assertEquals("Pool should have 0 available", 0, issuePlan.size())
        BigDecimal totalPlan = issuePlan.inject(BigDecimal.ZERO) { sum, it -> sum + (it.quantity ?: BigDecimal.ZERO) }
        assertEquals("Issue Plan (Pool Sources) should be 0 (all stolen)", 0.0, totalPlan.doubleValue(), 0.001)
        
        // Check sources: Should target V1 and V2 since pool is depleted by O3
        def v1Impact = impactList.find { it.victimWorkEffortId == v1 }
        assertNotNull("Victim 1 should be in the Impact List", v1Impact)
        assertEquals("Victim 1 impact type should be STOLEN", "STOLEN", v1Impact.type)
    }

    void testS3_5_VictimSanityGuard() {
        String facId = "WH_S3_5"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S3_5", 20.0)
        setUpInventory(facId, "II_MAT_C_COST", "II_MAT_C_S3_5", 100.0)
        // 1. Setup Victim: Need 20. 
        // Create Backorder (20 QNA)
        String victimWeId = setUpProductionRun("II_PROD_MANUF", 10.0, facId) // Needs 20 MAT_A
        // Since WH_S3_5 has stock, setUpProductionRun created a REAL reservation.
        // Let's manually create a BACKORDER to simulate the "Dirty" state.
        dispatcher.runSync("reserveWorkEffortInventoryItem", [
            workEffortId: victimWeId, productId: "II_MAT_A_COST", facilityId: facId,
            inventoryItemId: null, // NULL creates a separate logical backorder (Global Pool)
            quantity: 20.0, quantityNotAvailable: 20.0, 
            requireInventory: "N", userLogin: userLogin
        ])
        
        // Assert "Dirty" state: Task has 40 reserved for 20 need.
        def resCount = from("WorkEffortInvRes").where("workEffortId", victimWeId, "productId", "II_MAT_A_COST").queryCount()
        assertEquals("Victim should have 2 records (Dirty State)", 2, resCount)

        // 2. Setup Thief: Steals 10.
        String thiefWeId = setUpProductionRun("II_PROD_MANUF", 5.0, facId) // Needs 10 MAT_A.
        // Pool is now 80 (100 - 20 from victim). Thief will take from pool normally.
        // To force theft path directly, we call reallocateAndIssueInventory with an explicit impact.
        
        // 3. Action: Explicit Reallocation
        // Generate impact for 10 units specifically from the victim
        List impactList = [[
            productId: "II_MAT_A_COST", impactType: "Production Task", impactId: victimWeId,
            inventoryItemId: "II_MAT_A_S3_5", quantity: 10.0, type: "STOLEN", victimWorkEffortId: victimWeId
        ]]
        
        Map reallocResult = dispatcher.runSync("reallocateAndIssueInventory", [
                workEffortId: thiefWeId, impactList: impactList, userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(reallocResult)
        
        // 4. Assert: Sanity Guard should have cleaned up the redundant backorder.
        def finalResCount = from("WorkEffortInvRes").where("workEffortId", victimWeId, "productId", "II_MAT_A_COST").queryCount()
        assertEquals("Victim should now have exactly 1 record (Sanity Guard worked)", 1, finalResCount)
        
        def remainingRes = from("WorkEffortInvRes").where("workEffortId", victimWeId, "productId", "II_MAT_A_COST").queryFirst()
        BigDecimal totalCommited = (remainingRes.getBigDecimal("quantity") ?: BigDecimal.ZERO)
        assertEquals("Total commitment should match Requirement (20)", 20.0, totalCommited.doubleValue(), 0.001)
    }

    void testS3_6_ExplicitReallocationWorkflow() {
        String facId = "WH_S3_6"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S3_6", 100.0)
        setUpInventory(facId, "II_MAT_C_COST", "II_MAT_C_S3_6", 100.0)

        // 1. Setup: Pool has 100 units.
        // Victim holds all 100 units.
        String victimWeId = setUpProductionRun("II_PROD_MANUF", 50.0, facId) // Needs 100 MAT_A
        assertTrinityOfTruth("II_MAT_A_COST", victimWeId, 0.0, 100.0, 0.0, facId) // 0 ATP remains
        
        // Thief Needs 25 Finished Goods -> 50 MAT_A.
        String thiefWeId = setUpProductionRun("II_PROD_MANUF", 25.0, facId) 
        
        // 2. Action: Audit Impact (The "Plan")
        Map impactResult = dispatcher.runSync("getProductionRunTaskForceIssueImpact", [
            workEffortId: thiefWeId, facilityId: facId, userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(impactResult)
        List impactList = (List) impactResult.impactList
        BigDecimal plannedTheft = impactList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.quantity ?: BigDecimal.ZERO) }
        assertEquals("Audit should plan to steal 50 units", 50.0, plannedTheft.doubleValue(), 0.001)
        
        // 3. Action: Execute Reallocation (The "Reality")
        Map reallocResult = dispatcher.runSync("reallocateAndIssueInventory", [
            workEffortId: thiefWeId,
            impactList: impactList,
            userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(reallocResult)
        
        // 4. Parity Audit: Verify Trinity for Thief
        // Thief should have 50 issued, 0 reservation remains.
        // ATP = QOH - net_physical_res = 50 - 50 = 0.
        // Victim still has qty=100, QNA=50 → net physical claim = 50 (backed by the remaining 50 QOH).
        // The victim's backorder (QNA=50) tracks unmet debt but does NOT reduce the physical backing.
        assertTrinityOfTruth("II_MAT_A_COST", thiefWeId, 50.0, 0.0, 0.0, facId)
        
        // 5. Parity Audit: Verify Trinity for Victim
        // Victim should have 0 issued, 100 reservation remains (but now 50 is quantityNotAvailable).
        def victimResList = from("WorkEffortInvRes").where("workEffortId", victimWeId, "productId", "II_MAT_A_COST").queryList()
        BigDecimal victimQna = victimResList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.getBigDecimal("quantityNotAvailable") ?: 0) }
        assertEquals("Victim should be backordered by 50 units", 50.0, victimQna.doubleValue(), 0.001)
        
        // 6. Global Integrity Audit: Physical Stock vs Ledger
        def invItem = from("InventoryItem").where("inventoryItemId", "II_MAT_A_S3_6").queryOne()
        assertEquals("Physical QOH should be 50", 50.0, invItem.getBigDecimal("quantityOnHandTotal").doubleValue(), 0.001)
        assertEquals("Accounting Total should be 50", 50.0, invItem.getBigDecimal("accountingQuantityTotal").doubleValue(), 0.001)
    }

    void testS4_1_ReleaseWithRestore() {
        String facId = "WH_S4_1"
        createOrStoreFacility([facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y", allowInventoryTheft: "Y", reconcilePrunBackorders: "N"])
        setUpInventory(facId, "II_MAT_A_COST", "II_MAT_A_S4_1", 100.0)
        String weId = setUpProductionRun("II_PROD_MANUF", 10.0, facId) // Needs 20 MAT_A.
        
        // Capture count BEFORE
        long countBefore = from("InventoryItemDetail").where("inventoryItemId", "II_MAT_A_S4_1").queryCount()

        // Release with restoration (default)
        Map result = dispatcher.runSync("releaseProductionRunTaskComponent", [
                workEffortId: weId, productId: "II_MAT_A_COST", 
                inventoryItemId: "II_MAT_A_S4_1",
                appendInventoryItemDetail: true, userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(result)
        
        // Assert: Detail Record CREATED
        long countAfter = from("InventoryItemDetail").where("inventoryItemId", "II_MAT_A_S4_1").queryCount()
        assertEquals("InventoryItemDetail record should be created for restoration", countBefore + 1, countAfter)

        // Assert: Reservation GONE
        def res = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_A_COST").queryOne()
        assert res == null
    }

    void testS4_2_ReleaseSatisfaction() {
        String weId = setUpProductionRun("II_PROD_MANUF", 10.0) // Needs 20 MAT_A.
        
        // Capture count BEFORE
        long countBefore = from("InventoryItemDetail").where("inventoryItemId", "II_MAT_A_TEST_INV").queryCount()

        // Action: Release without restoration (satisfaction scenario)
        // Find the actual item reserved by setUpProductionRun to ensure we target the right record
        def resRecord = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_A_COST").queryFirst()
        assert resRecord != null : "Reservation should have been created by setUpProductionRun"
        String reservedItemId = resRecord.inventoryItemId

        Map result = dispatcher.runSync("releaseProductionRunTaskComponent", [
                workEffortId: weId, productId: "II_MAT_A_COST", 
                inventoryItemId: reservedItemId,
                appendInventoryItemDetail: false, userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(result)
        
        // Assert: Detail Record NOT created
        long countAfter = from("InventoryItemDetail").where("inventoryItemId", "II_MAT_A_TEST_INV").queryCount()
        assertEquals("InventoryItemDetail record should NOT be created for satisfaction", countBefore, countAfter)

        // Assert: Reservation GONE
        def res = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_A_COST").queryOne()
        assert res == null
    }

    // --- Category 5: Hardened Policy & Reconciliation ---

    void testS5_1_PolicyGateFail() {
        // Uses WH_TRADITIONAL (allowInventoryTheft = 'N') from XML
        String itemId = "II_INV_TRAD_S5_1"
        setUpInventory("WH_TRADITIONAL", "II_MAT_A_COST", itemId, 100.0)
        setUpInventory("WH_TRADITIONAL", "II_MAT_C_COST", "II_INV_TRAD_C_S5_1", 100.0)
        
        // Setup Victim: Takes all 100 units.
        String victimWeId = setUpProductionRun("II_PROD_MANUF", 50.0, "WH_TRADITIONAL") // Needs 100 MAT_A
        assertTrinityOfTruth("II_MAT_A_COST", victimWeId, 0.0, 100.0, 0.0, "WH_TRADITIONAL")
        
        // Setup Thief: Needs 20 more. Physical stock exists (100) but is NOT available to us.
        String thiefWeId = setUpProductionRun("II_PROD_MANUF", 10.0, "WH_TRADITIONAL") // Needs 20 MAT_A
        
        // Action: Try to force issue in a TRADITIONAL warehouse.
        // Run in new transaction so a service error does NOT poison the outer tx for subsequent tests.
        Map result = dispatcher.runSync("issueProductionRunTask", [
                workEffortId: thiefWeId,
                failIfItemsAreNotAvailable: "N",
                userLogin: userLogin
        ], 0, true)

        // Assert: Failure! Policy forbids theft.
        assert ServiceUtil.isError(result)
        assertTrue("Error message should contain theft policy violation", ServiceUtil.getErrorMessage(result).contains("forbids inventory theft"))
    }

    void testS5_2_APIPolicyEnforcement() {
        // Uses WH_TRADITIONAL (allowInventoryTheft = 'N') from XML
        String itemId = "II_INV_TRAD_S5_2"
        String thiefWeId = setUpProductionRun("II_PROD_MANUF", 10.0, "WH_TRADITIONAL")
        setUpInventory("WH_TRADITIONAL", "II_MAT_A_COST", itemId, 100.0)
        setUpInventory("WH_TRADITIONAL", "II_MAT_C_COST", "II_INV_TRAD_C_S5_2", 100.0)
        
        // Action: Try to call reallocateAndIssueInventory directly with an impactList (bypassing UI).
        // Run in new transaction so a service error does NOT poison the outer tx.
        List impactList = [[
            productId: "II_MAT_A_COST", impactType: "Production Task", impactId: "SOME_VICTIM",
            inventoryItemId: itemId, quantity: 10.0, type: "STOLEN", victimWorkEffortId: "SOME_VICTIM"
        ]]

        Map reallocResult = dispatcher.runSync("reallocateAndIssueInventory", [
                workEffortId: thiefWeId, impactList: impactList, facilityId: "WH_TRADITIONAL", userLogin: userLogin
        ], 0, true)

        // Assert: Failure! The service-level security guard should block the theft.
        assert ServiceUtil.isError(reallocResult)
        assertTrue(reallocResult.errorMessage.contains("Policy Violation"))
    }

    void testS5_3_NightlyReconciliation() {
        // Uses WH_FLUID (Theft=Y, Recon=Y) from XML
        // 1. Setup Auditor Case: Phantom backorder
        String itemIdA = "II_INV_FLUID_A_S5_3"
        String itemIdC = "II_INV_FLUID_C_S5_3"
        setUpInventory("WH_FLUID", "II_MAT_A_COST", itemIdA, 100.0)
        setUpInventory("WH_FLUID", "II_MAT_C_COST", itemIdC, 0.0)
        String victimWeId = setUpProductionRun("II_PROD_MANUF", 10.0, "WH_FLUID") // Needs 20 MAT_A
        dispatcher.runSync("reserveWorkEffortInventoryItem", [
            workEffortId: victimWeId, productId: "II_MAT_A_COST", facilityId: "WH_FLUID",
            inventoryItemId: itemIdA,
            quantity: 20.0, quantityNotAvailable: 20.0, 
            requireInventory: "N", userLogin: userLogin
        ])

        // 2. Setup Satisfier Case: Real backorder (needed but out of stock)
        String backorderWeId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_FLUID") // Needs 5 MAT_C
        dispatcher.runSync("issueProductionRunTask", [workEffortId: backorderWeId, userLogin: userLogin])
        // Result: 5 units backordered for MAT_C

        // 3. Stock arrival for MAT_C
        dispatcher.runSync("createInventoryItemDetail", [
                inventoryItemId: itemIdC,
                quantityOnHandDiff: 50.0, availableToPromiseDiff: 50.0, accountingQuantityDiff: 50.0,
                userLogin: userLogin
        ])

        // 4. Action: Reconciliation
        dispatcher.runSync("reconcileInventoryForProductionJobs", [userLogin: userLogin])

        // 5. Assert Auditor: Phantom debt (A) should be purged
        def resA = from("WorkEffortInvRes").where("workEffortId", victimWeId, "productId", "II_MAT_A_COST").queryFirst()
        // If purged completely, it might be 0 or deleted. Our logic currently leaves the record with 0.
        assertNotNull("Auditor reservation should exist", resA)
        assertEquals("Auditor should have purged phantom debt", 0.0, (resA.getBigDecimal("quantityNotAvailable") ?: 0).doubleValue(), 0.001)

        // 6. Assert Satisfier: Valid backorder (C) settled
        def boRes = from("WorkEffortInvRes").where("workEffortId", backorderWeId, "productId", "II_MAT_C_COST").queryFirst()
        assertNotNull("Satisfier reservation should exist", boRes)
        assertEquals("Satisfier should have settled the debt", 0.0, (boRes.getBigDecimal("quantityNotAvailable") ?: 0).doubleValue(), 0.001)
        assertEquals("Physical reservation should now be 5", 5.0, (boRes.getBigDecimal("quantity") ?: 0).doubleValue(), 0.001)
    }

    void testS5_4_ReconFluidPolicy() {
        // 1. Setup: Est=10, Issued=5. 
        String itemId = "II_INV_FLUID_A_S5_4"
        setUpInventory("WH_FLUID", "II_MAT_A_COST", itemId, 5.0)
        setUpInventory("WH_FLUID", "II_MAT_C_COST", "II_INV_FLUID_C_S5_4", 100.0)
        setUpInventory("WH_FLUID", "II_MAT_A_COST", "II_INV_FLUID_B_S5_4", 0.0, false) // Initialize early without purging itemId
        
        String victimWeId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_FLUID") // Needs 10 MAT_A
        
        // Issue 5 MAT_A (satisfies the physical part while leaving 5 MAT_A backordered)
        Map issueVictimResult = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: victimWeId, productId: "II_MAT_A_COST", quantity: 5.0,
            failIfItemsAreNotAvailable: "N", userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(issueVictimResult)
        
        // 2. Auditor Setup: Manually add "Excess" backorder (7 logical units)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [
            workEffortId: victimWeId, productId: "II_MAT_A_COST", facilityId: "WH_FLUID", 
            inventoryItemId: itemId,
            quantity: 7.0, quantityNotAvailable: 7.0, requireInventory: "N", userLogin: userLogin
        ])

        // 3. Satisfier Setup: Valid backorder + stock available
        String boWeId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_FLUID") // Needs 10 MAT_A
        
        // 3. Action
        dispatcher.runSync("reconcileInventoryForProductionJobs", [userLogin: userLogin])
        
        // 4. Assert Auditor: issued 5 plus reserved commitments exceed the remaining demand.
        // The auditor purges available logical debt first, so no MAT_A QNA should remain.
        def resList = from("WorkEffortInvRes").where("workEffortId", victimWeId).queryList()
        BigDecimal totalQna = resList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.getBigDecimal("quantityNotAvailable") ?: 0) }
        assertEquals("Aggressive Auditor should have purged excess logical debt to match estimate ceiling", 5.0, totalQna.doubleValue(), 0.001)

        // 5. Assert Satisfier: Should have settled backorders if stock is restored
        dispatcher.runSync("createInventoryItemDetail", [
                inventoryItemId: "II_INV_FLUID_B_S5_4",
                quantityOnHandDiff: 20.0, availableToPromiseDiff: 20.0, accountingQuantityDiff: 20.0,
                userLogin: userLogin
        ])
        dispatcher.runSync("reconcileInventoryForProductionJobs", [userLogin: userLogin])
        
        def boRes = from("WorkEffortInvRes").where("workEffortId", boWeId, "productId", "II_MAT_A_COST").queryFirst()
        assertNotNull("Backorder reservation should exist for boWeId", boRes)
        assertEquals("Satisfier should have settled the debt in Fluid Warehouse", 0.0, (boRes.getBigDecimal("quantityNotAvailable") ?: 0).doubleValue(), 0.001)
    }

    void testS5_5_ReconTraditionalPolicy() {
        // 1. Auditor Setup: Est=10, Issued=12 (Scrap).
        String itemId = "II_INV_TRAD_A_S5_5"
        setUpInventory("WH_TRADITIONAL", "II_MAT_A_COST", itemId, 20.0)
        setUpInventory("WH_TRADITIONAL", "II_MAT_C_COST", "II_INV_TRAD_C_S5_5", 100.0)
        setUpInventory("WH_TRADITIONAL", "II_MAT_A_COST", "II_INV_TRAD_B_S5_5", 0.0, false) // Initialize early without purging itemId
        
        String weId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_TRADITIONAL") // Needs 10 MAT_A
        
        // Issue 12 from real available stock to model scrap without using theft mode.
        Map issueScrapResult = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: weId, productId: "II_MAT_A_COST", quantity: 12.0,
            failIfItemsAreNotAvailable: "Y", 
            userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(issueScrapResult)
        
        // Manually add logical debt for "future scrap needs" (2 logical units)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [
            workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_TRADITIONAL", 
            inventoryItemId: itemId,
            quantity: 2.0, quantityNotAvailable: 2.0, requireInventory: "N", userLogin: userLogin
        ])

        // 2. Satisfier Setup: Valid backorder + stock available
        String boWeId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_TRADITIONAL") // Needs 10 MAT_A
        dispatcher.runSync("issueProductionRunTask", [workEffortId: boWeId, userLogin: userLogin])
        // Use createInventoryItemDetail instead of setUpInventory to avoid nuking reservations
        dispatcher.runSync("createInventoryItemDetail", [
                inventoryItemId: "II_INV_TRAD_B_S5_5",
                quantityOnHandDiff: 20.0, availableToPromiseDiff: 20.0, accountingQuantityDiff: 20.0,
                userLogin: userLogin
        ])
        
        // 3. Action
        dispatcher.runSync("reconcileInventoryForProductionJobs", [userLogin: userLogin])
        
        // 4. Assert Auditor: Conservative mode should NOT purge the 2 because Reserved+QNA (2) <= Est (10)
        def res = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_A_COST", "inventoryItemId", itemId).queryFirst()
        assertNotNull("Reservation record should exist", res)
        assertEquals("Conservative Auditor should have spared the scrap backorder", 2.0, res.getBigDecimal("quantityNotAvailable").doubleValue(), 0.001)

        // 5. Assert Satisfier: Should NOT settle because reconcilePrunBackorders=N
        def boResList = from("WorkEffortInvRes").where("workEffortId", boWeId, "productId", "II_MAT_A_COST").queryList()
        assertTrue("Reservation should exist for boWeId", !boResList.isEmpty())
        BigDecimal boQna = boResList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.getBigDecimal("quantityNotAvailable") ?: 0) }
        assertEquals("Satisfier should NOT have settled in Traditional Warehouse", 2.0, boQna.doubleValue(), 0.001)
    }

    void testS5_6_ReconSafePolicy() {
        // Safe Recovery = Aggressive Auditor + Conservative Satisfier
        // 1. Setup Auditor: Est=10, Issued=12 (Scrap), QNA=5. Aggressive mode should purge all 5.
        setUpInventory("WH_SAFE", "II_MAT_A_COST", "INV_SAFE_A", 20.0)
        setUpInventory("WH_SAFE", "II_MAT_C_COST", "INV_SAFE_C_HEAL", 0.0)
        String weId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_SAFE") // Needs 10 MAT_A
        Map issueScrapResult = dispatcher.runSync("issueProductionRunTaskComponent", [workEffortId: weId, productId: "II_MAT_A_COST", quantity: 12.0, failIfItemsAreNotAvailable: "Y", userLogin: userLogin])
        assert ServiceUtil.isSuccess(issueScrapResult)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_SAFE", inventoryItemId: "INV_SAFE_A", quantity: 5.0, quantityNotAvailable: 5.0, requireInventory: "N", userLogin: userLogin])
        
        // 2. Setup Satisfier: boWeId needs 5 MAT_C. Stock exists.
        String boWeId = setUpProductionRun("II_PROD_MANUF", 5.0, "WH_SAFE") // Needs 5 MAT_C
        dispatcher.runSync("issueProductionRunTask", [workEffortId: boWeId, userLogin: userLogin])
        // Use createInventoryItemDetail instead of setUpInventory to avoid nuking reservations
        dispatcher.runSync("createInventoryItemDetail", [
                inventoryItemId: "INV_SAFE_C_HEAL",
                quantityOnHandDiff: 20.0, availableToPromiseDiff: 20.0, accountingQuantityDiff: 20.0,
                userLogin: userLogin
        ])

        // 3. Action
        dispatcher.runSync("reconcileInventoryForProductionJobs", [userLogin: userLogin])
        
        // 4. Assert Auditor: Aggressive (Safe mode ceiling = Est - Issued = 0). Should purge all 5.
        def resList = from("WorkEffortInvRes").where("workEffortId", weId, "productId", "II_MAT_A_COST").queryList()
        BigDecimal totalQna = resList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.getBigDecimal("quantityNotAvailable") ?: 0) }
        assertEquals("Safe Mode (Aggressive Auditor) should have purged the excess", 0.0, totalQna.doubleValue(), 0.001)

        // 5. Assert Satisfier: Conservative (Should heal because reconcilePrunBackorders=Y)
        def boResList = from("WorkEffortInvRes").where("workEffortId", boWeId, "productId", "II_MAT_C_COST").queryList()
        BigDecimal boQna = boResList.inject(BigDecimal.ZERO) { sum, it -> sum + (it.getBigDecimal("quantityNotAvailable") ?: 0) }
        assertEquals("Safe Mode (Conservative Satisfier) should have healed backorder", 0.0, boQna.doubleValue(), 0.001)
    }

    // --- Category 6: Manual Issuance Overrides & Policy Matrix ---

    void testM1_ManualLotTheftSuccess() {
        // Setup: Item in Fluid Warehouse (Theft=Y). Reserved by Victim.
        setUpInventory("WH_FLUID", "II_MAT_A_COST", "II_MAN_FLUID", 10.0)
        // Set manual attributes on the created item
        GenericValue item = from("InventoryItem").where("inventoryItemId", "II_MAN_FLUID").queryOne()
        item.set("locationSeqId", "FLOC_01")
        item.set("lotId", "LOT_MANUAL_01")
        item.store()
        
        // Victim reserves all 10
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: "WE_VICTIM", productId: "II_MAT_A_COST", facilityId: "WH_FLUID", inventoryItemId: "II_MAN_FLUID", quantity: 10.0, userLogin: userLogin])
        
        // Action: Attacker issues via Lot ID (Manual Override) with failIf='N'
        Map result = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: "WE_ATTACKER_FLUID", productId: "II_MAT_A_COST", 
            lotId: "LOT_MANUAL_01", quantity: 5.0,
            failIfItemsAreNotAvailable: "N", userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(result)
        
        // Assert: 5 units issued to Attacker. Victim reconciled.
        // ATP is 0 because Victim's 5 remaining units are now backordered (QNA=5).
        assertTrinityOfTruth("II_MAT_A_COST", "WE_ATTACKER_FLUID", 5.0, 0.0, 0.0, "WH_FLUID")
        def victimRes = from("WorkEffortInvRes").where(workEffortId: "WE_VICTIM").queryFirst()
        assertEquals("Victim should be backordered by 5", 5.0, victimRes.getBigDecimal("quantityNotAvailable").doubleValue(), 0.001)
    }

    void testM2_ManualForceNegative() {
        // Setup: Item has 0 stock.
        setUpInventory("WH_FLUID", "II_MAT_A_COST", "II_MAN_FLUID", 0.0)
        
        // Action: Issue 10 via Manual Override (Inventory Item ID) with failIf='N'
        Map result = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: "WE_ATTACKER_FLUID", productId: "II_MAT_A_COST", 
            inventoryItemId: "II_MAN_FLUID", quantity: 10.0,
            failIfItemsAreNotAvailable: "N", failIfItemsAreNotOnHand: "N", userLogin: userLogin
        ])
        assert ServiceUtil.isSuccess(result)
        
        // Assert: Negative QOH
        def item = from("InventoryItem").where(inventoryItemId: "II_MAN_FLUID").queryOne()
        assertEquals("QOH should be -10", -10.0, item.getBigDecimal("quantityOnHandTotal").doubleValue(), 0.001)
    }

    void testM3_UserStrictnessOverridesFacilityFluidity() {
        // Setup: Fluid Facility (Theft=Y). Item fully reserved by Victim.
        setUpInventory("WH_FLUID", "II_MAT_A_COST", "II_MAN_FLUID", 10.0)
        GenericValue item = from("InventoryItem").where("inventoryItemId", "II_MAN_FLUID").queryOne()
        item.set("lotId", "LOT_MANUAL_01")
        item.store()
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: "WE_VICTIM", productId: "II_MAT_A_COST", facilityId: "WH_FLUID", inventoryItemId: "II_MAN_FLUID", quantity: 10.0, userLogin: userLogin])
        
        // Action: Attacker tries manual issue but passes failIf='Y' (User Strictness).
        // Run in new transaction so a service error does NOT poison the outer tx.
        Map result = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: "WE_ATTACKER_FLUID", productId: "II_MAT_A_COST",
            lotId: "LOT_MANUAL_01", quantity: 5.0,
            failIfItemsAreNotAvailable: "Y", userLogin: userLogin
        ], 0, true)

        // Assert: Error! User preference ('Y') must be respected even in a 'Y' facility.
        assert ServiceUtil.isError(result)
        String error = ServiceUtil.getErrorMessage(result)
        assertTrue("Error should contain 'Materials Not Available' or 'promised to other tasks'. Got: ${error}",
            error.contains("Materials Not Available") || error.contains("shortfall") || error.contains("promised to other tasks"))
    }

    void testM4_FacilityStrictnessOverridesUserFluidity() {
        // Setup: Traditional Warehouse (Theft=N). Item fully reserved by Victim.
        setUpInventory("WH_TRADITIONAL", "II_MAT_A_COST", "II_MAN_STRICT", 10.0)
        GenericValue item = from("InventoryItem").where("inventoryItemId", "II_MAN_STRICT").queryOne()
        item.set("locationSeqId", "TLOC_01")
        item.store()
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: "WE_VICTIM", productId: "II_MAT_A_COST", facilityId: "WH_TRADITIONAL", inventoryItemId: "II_MAN_STRICT", quantity: 10.0, userLogin: userLogin])
        
        // Action: Attacker tries manual issue with failIf='N' (User Fluidity).
        // Run in new transaction so a service error does NOT poison the outer tx.
        Map result = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: "WE_ATTACKER_STRICT", productId: "II_MAT_A_COST",
            locationSeqId: "TLOC_01", quantity: 5.0,
            failIfItemsAreNotAvailable: "N", userLogin: userLogin
        ], 0, true)

        // Assert: Error! Facility policy ('N') must be respected even if user says 'N'.
        assert ServiceUtil.isError(result)
        String error = ServiceUtil.getErrorMessage(result)
        assertTrue("Error should contain 'Materials Not Available' or 'forbids inventory theft'. Got: ${error}",
            error.contains("Materials Not Available") || error.contains("shortfall") || error.contains("forbids inventory theft"))
    }

    void testM6_SelfConsumptionSuccessInStrictMode() {
        // Setup: Traditional Facility (Theft=N). 
        setUpInventory("WH_TRADITIONAL", "II_MAT_A_COST", "II_MAN_STRICT", 10.0)
        
        // Attacker reserves for THEMSELVES
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: "WE_ATTACKER_STRICT", productId: "II_MAT_A_COST", facilityId: "WH_TRADITIONAL", inventoryItemId: "II_MAN_STRICT", quantity: 10.0, userLogin: userLogin])
        
        // Action: Attacker issues their OWN reserved stock in a strict facility.
        Map result = dispatcher.runSync("issueProductionRunTaskComponent", [
            workEffortId: "WE_ATTACKER_STRICT", productId: "II_MAT_A_COST", 
            inventoryItemId: "II_MAN_STRICT", quantity: 5.0,
            failIfItemsAreNotAvailable: "Y", userLogin: userLogin
        ])
        
        // Assert: Success! Self-blocking must be avoided.
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", "WE_ATTACKER_STRICT", 5.0, 5.0, 0.0, "WH_TRADITIONAL")
    }

    // --- Category 6: No Auto-Reservation & Various Inventory Levels ---

    void testS6_1_ManualReserve_IssueDefault_Success() {
        // Setup: Sufficient Stock (100). Auto-Res=N.
        String weId = setUpNoAutoReserveProductionRun("II_S6_1", 100.0)
        // Manual Reservation
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_NO_AUTO_RES", inventoryItemId: "II_S6_1", quantity: 20.0, userLogin: userLogin])
        
        // Action: Issue (Default)
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, 80.0, "WH_NO_AUTO_RES")
    }

    void testS6_2_ManualReserve_IssueFailIfAvail_Y_Success() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_2", 100.0)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_NO_AUTO_RES", inventoryItemId: "II_S6_2", quantity: 20.0, userLogin: userLogin])
        
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "Y", userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, 80.0, "WH_NO_AUTO_RES")
    }

    void testS6_3_ManualReserve_IssueFailIfAvail_N_Success() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_3", 100.0)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_NO_AUTO_RES", inventoryItemId: "II_S6_3", quantity: 20.0, userLogin: userLogin])
        
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "N", failIfItemsAreNotOnHand: "N", userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, 80.0, "WH_NO_AUTO_RES")
    }

    void testS6_4_NoReserve_DirectIssueDefault_Success() {
        // Setup: Sufficient Stock. NO RESERVATION.
        String weId = setUpNoAutoReserveProductionRun("II_S6_4", 100.0)
        
        // Action: Direct Issue (Default)
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, 80.0, "WH_NO_AUTO_RES")
    }

    void testS6_5_NoReserve_DirectIssueFailIfAvail_Y_Success() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_5", 100.0)
        
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "Y", userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, 80.0, "WH_NO_AUTO_RES")
    }

    void testS6_6_NoReserve_DirectIssueFailIfAvail_N_Success() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_6", 100.0)
        
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "N", failIfItemsAreNotOnHand: "N", userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, 80.0, "WH_NO_AUTO_RES")
    }

    void testS6_7_Insufficient_ManualReserve_IssueDefault_Fail() {
        // Setup: Zero Stock.
        String weId = setUpNoAutoReserveProductionRun("II_S6_7", 0.0)
        // Manual Reservation (logs shortage)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_NO_AUTO_RES", inventoryItemId: "II_S6_7", quantity: 20.0, userLogin: userLogin])
        
        // Action: Issue (Default) - Should fail because ATP is not available
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, userLogin: userLogin], 0, true)
        assert ServiceUtil.isError(result)
    }

    void testS6_8_Insufficient_ManualReserve_IssueFailIfAvail_Y_Fail() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_8", 0.0)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_NO_AUTO_RES", inventoryItemId: "II_S6_8", quantity: 20.0, userLogin: userLogin])
        
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "Y", userLogin: userLogin], 0, true)
        assert ServiceUtil.isError(result)
    }

    void testS6_9_Insufficient_ManualReserve_IssueFailIfAvail_N_Success() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_9", 0.0)
        dispatcher.runSync("reserveWorkEffortInventoryItem", [workEffortId: weId, productId: "II_MAT_A_COST", facilityId: "WH_NO_AUTO_RES", inventoryItemId: "II_S6_9", quantity: 20.0, userLogin: userLogin])
        
        // Action: Issue (failIf='N') - Success (Theft allowed)
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "N", failIfItemsAreNotOnHand: "N", userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        // QOH becomes -20. ATP becomes -20.
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, -20.0, "WH_NO_AUTO_RES")
    }

    void testS6_10_Insufficient_NoReserve_DirectIssueDefault_Fail() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_10", 0.0)
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, userLogin: userLogin], 0, true)
        assert ServiceUtil.isError(result)
    }

    void testS6_11_Insufficient_NoReserve_DirectIssueFailIfAvail_Y_Fail() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_11", 0.0)
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "Y", userLogin: userLogin], 0, true)
        assert ServiceUtil.isError(result)
    }

    void testS6_12_Insufficient_NoReserve_DirectIssueFailIfAvail_N_Success() {
        String weId = setUpNoAutoReserveProductionRun("II_S6_12", 0.0)
        Map result = dispatcher.runSync("issueProductionRunTask", [workEffortId: weId, failIfItemsAreNotAvailable: "N", failIfItemsAreNotOnHand: "N", userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
        assertTrinityOfTruth("II_MAT_A_COST", weId, 20.0, 0.0, -20.0, "WH_NO_AUTO_RES")
    }

    void testS5_7_NullPolicyDefaultsToStrict() {
        String facId = "WH_NULL_POLICY"
        // Create facility EXCLUDING the allowInventoryTheft flag
        createOrStoreFacility([
            facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y"
        ])
        
        String thiefWeId = setUpProductionRun("II_PROD_MANUF", 10.0, facId)
        
        // Action: Attempt impact analysis
        Map impactResult = dispatcher.runSync("getProductionRunTaskForceIssueImpact", [
            workEffortId: thiefWeId, facilityId: facId, productId: "II_MAT_A_COST", userLogin: userLogin
        ])
        
        // Assert: It should succeed but contain a policyViolation message
        assert ServiceUtil.isSuccess(impactResult)
        assertNotNull("Should contain a policyViolation message", impactResult.policyViolation)
        assertTrue("Message should explain that theft is forbidden",
                   impactResult.policyViolation.contains("forbidden"))
    }

    void testFacilityPolicyPersistence() {
        String facId = "WH_POLICY_TEST"
        createOrStoreFacility([
            facilityId: facId, facilityName: facId, facilityTypeId: "WAREHOUSE",
            ownerPartyId: "II_COMPANY", autoReservePrun: "Y",
            allowInventoryTheft: "Y", reconcilePrunBackorders: "Y"
        ])

        GenericValue facility = from("Facility").where("facilityId", facId).queryOne()
        assertNotNull("Facility should be created", facility)
        assertEquals("allowInventoryTheft should be Y", "Y", facility.allowInventoryTheft)
        assertEquals("reconcilePrunBackorders should be Y", "Y", facility.reconcilePrunBackorders)

        facility.set("allowInventoryTheft", "N")
        facility.set("reconcilePrunBackorders", "N")
        facility.store()

        facility.refresh()
        assertEquals("allowInventoryTheft should be N after update", "N", facility.allowInventoryTheft)
        assertEquals("reconcilePrunBackorders should be N after update", "N", facility.reconcilePrunBackorders)
    }
}
