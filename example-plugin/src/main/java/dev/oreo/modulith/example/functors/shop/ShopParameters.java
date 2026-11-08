package dev.oreo.modulith.example.functors.shop;

/** Independent parameters for the shop demonstration module. */
public record ShopParameters(double taxRate, String currency) {
    public ShopParameters {
        if (!Double.isFinite(taxRate) || taxRate < 0 || taxRate > 1) {
            throw new IllegalArgumentException("taxRate must be between 0 and 1");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency cannot be blank");
        }
    }
}
