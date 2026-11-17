package rooms;

import org.junit.Test;

public class DomainSuite {
    @Test public void registrationAndLogout() throws Exception {
        new StoreTest().registrationAndLogout();
    }

    @Test public void bookingOwnership() throws Exception {
        new StoreTest().bookingOwnership();
    }

    @Test public void equipmentAndCapacity() throws Exception {
        new StoreTest().equipmentAndCapacity();
    }

    @Test public void maintenanceAndRescheduleRollback() throws Exception {
        new StoreTest().maintenanceAndRescheduleRollback();
    }

    @Test public void disabledRooms() throws Exception {
        new StoreTest().disabledRooms();
    }

    @Test public void concurrentMaintenanceAndBookings() throws Exception {
        new StoreTest().concurrentMaintenanceAndBookings();
    }

    @Test public void concurrentBookingsAndAdjacentSlots() throws Exception {
        new StoreTest().concurrentBookingsAndAdjacentSlots();
    }

    @Test public void bookingHorizon() throws Exception {
        new Acceptance().bookingHorizon();
    }

    @Test public void loginLimits() throws Exception {
        new Acceptance().loginLimits();
    }

    @Test public void calendarOverrides() throws Exception {
        new Acceptance().calendarOverrides();
    }

    @Test public void recurringRollbackAndReschedule() throws Exception {
        new Acceptance().recurringRollbackAndReschedule();
    }

    @Test public void checkinAndPenalties() throws Exception {
        new Acceptance().checkinAndPenalties();
    }

    @Test public void staleScanPreservesRescheduledBooking() throws Exception {
        StaleScanTest.run();
    }

    @Test public void migrationRepairsHistoryAndSurvivesRestart() throws Exception {
        CheckinMigrationTest.run();
    }
}
