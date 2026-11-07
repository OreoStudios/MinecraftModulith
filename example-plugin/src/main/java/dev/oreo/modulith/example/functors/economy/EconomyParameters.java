package dev.oreo.modulith.example.functors.economy;

/** Independent parameters for the economy demonstration module. */
public record EconomyParameters(String currency, long startingBalance) {
    public EconomyParameters {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency cannot be blank");
        }
        if (startingBalance < 0) {
            throw new IllegalArgumentException("startingBalance must be nonnegative");
        }
    }
}
