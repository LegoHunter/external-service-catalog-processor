package io.legohunter.ingress.common.util;

public class KafkaNamingUtil {
    public static String kebabToCamel(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String[] parts = input.split("-");
        StringBuilder result = new StringBuilder(parts[0]);

        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                result.append(Character.toUpperCase(parts[i].charAt(0)))
                        .append(parts[i].substring(1));
            }
        }

        return result.toString();
    }
}
