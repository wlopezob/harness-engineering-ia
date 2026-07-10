package com.gentleman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DiscountServiceTest {

    @Test
    void appliesTenPercentDiscount() {
        DiscountService service = new DiscountService();

        assertEquals(90.0, service.finalPrice(100, 10));
    }

    @Test
    void appliesTwentyPercentDiscountOnDifferentPrice() {
        DiscountService service = new DiscountService();

        assertEquals(40.0, service.finalPrice(50, 20));
    }

    @Test
    void rejectsNegativeDiscount() {
        DiscountService service = new DiscountService();

        assertThrows(IllegalArgumentException.class, () -> service.finalPrice(100, -1));
    }

    @Test
    void rejectsDiscountAboveOneHundred() {
        DiscountService service = new DiscountService();

        assertThrows(IllegalArgumentException.class, () -> service.finalPrice(100, 101));
    }

    @Test
    void save10AppliesTenPercent() {
        DiscountService service = new DiscountService();

        assertEquals(90.0, service.applyCoupon(100, "SAVE10"));
    }

    @Test
    void save20AppliesTwentyPercent() {
        DiscountService service = new DiscountService();

        assertEquals(80.0, service.applyCoupon(100, "SAVE20"));
    }

    @Test
    void unknownCouponIsRejected() {
        DiscountService service = new DiscountService();

        assertThrows(IllegalArgumentException.class, () -> service.applyCoupon(100, "NOPE"));
    }

    @Test
    void nullCouponLeavesPriceUnchanged() {
        DiscountService service = new DiscountService();

        assertEquals(100.0, service.applyCoupon(100, null));
    }

    @Test
    void emptyCouponLeavesPriceUnchanged() {
        DiscountService service = new DiscountService();

        assertEquals(100.0, service.applyCoupon(100, ""));
    }

    @Test
    void combinesTwoCouponsInSequence() {
        DiscountService service = new DiscountService();

        assertEquals(72.0, service.applyCoupon(100, "SAVE10,SAVE20"));
    }

    @Test
    void combinesRepeatedCouponSequentially() {
        DiscountService service = new DiscountService();

        assertEquals(81.0, service.applyCoupon(100, "SAVE10,SAVE10"));
    }

    @Test
    void unknownCouponInCombinationIsRejected() {
        DiscountService service = new DiscountService();

        assertThrows(IllegalArgumentException.class, () -> service.applyCoupon(100, "SAVE10,NOPE"));
    }
}
