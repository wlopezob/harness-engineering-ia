package com.gentleman;

public class DiscountService {

    public double finalPrice(double price, int discountPercent) {
        if (discountPercent < 0 || discountPercent > 100) {
            throw new IllegalArgumentException("discountPercent must be between 0 and 100");
        }
        return price - (price * discountPercent / 100.0);
    }

    public double applyCoupon(double price, String code) {
        if (code == null || code.isEmpty()) {
            return price;
        }
        double result = price;
        for (String singleCode : code.split(",")) {
            result = applySingleCoupon(result, singleCode);
        }
        return result;
    }

    private double applySingleCoupon(double price, String code) {
        if (code.equals("SAVE10")) {
            return finalPrice(price, 10);
        }
        if (code.equals("SAVE20")) {
            return finalPrice(price, 20);
        }
        throw new IllegalArgumentException("unknown coupon code: " + code);
    }
}
