package fr.pipoumoney.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtil {
    private MoneyUtil() {}

    public static double round(double v, int decimals) {
        int d = Math.max(0, Math.min(8, decimals));
        return BigDecimal.valueOf(v).setScale(d, RoundingMode.HALF_UP).doubleValue();
    }
}
