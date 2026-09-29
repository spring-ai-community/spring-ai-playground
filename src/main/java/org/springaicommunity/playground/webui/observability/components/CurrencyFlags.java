/*
 * Copyright © 2025 Jemin Huh (hjm1980@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.playground.webui.observability.components;

public final class CurrencyFlags {

    private CurrencyFlags() {
    }

    public static String flagFor(String currencyCode) {
        if (currencyCode == null || currencyCode.length() < 3) return "🏳";
        return switch (currencyCode) {
            case "KRW" -> "🇰🇷";
            case "USD" -> "🇺🇸";
            case "EUR" -> "🇪🇺";
            case "CNY" -> "🇨🇳";
            case "JPY" -> "🇯🇵";
            case "INR" -> "🇮🇳";
            case "GBP" -> "🇬🇧";
            case "CHF" -> "🇨🇭";
            case "CAD" -> "🇨🇦";
            case "AUD" -> "🇦🇺";
            case "NZD" -> "🇳🇿";
            case "HKD" -> "🇭🇰";
            case "SGD" -> "🇸🇬";
            case "TWD" -> "🇹🇼";
            case "THB" -> "🇹🇭";
            case "IDR" -> "🇮🇩";
            case "VND" -> "🇻🇳";
            case "PHP" -> "🇵🇭";
            case "MYR" -> "🇲🇾";
            case "MXN" -> "🇲🇽";
            case "BRL" -> "🇧🇷";
            case "ARS" -> "🇦🇷";
            case "CLP" -> "🇨🇱";
            case "COP" -> "🇨🇴";
            case "PEN" -> "🇵🇪";
            case "ZAR" -> "🇿🇦";
            case "EGP" -> "🇪🇬";
            case "NGN" -> "🇳🇬";
            case "KES" -> "🇰🇪";
            case "TRY" -> "🇹🇷";
            case "RUB" -> "🇷🇺";
            case "UAH" -> "🇺🇦";
            case "PLN" -> "🇵🇱";
            case "CZK" -> "🇨🇿";
            case "HUF" -> "🇭🇺";
            case "SEK" -> "🇸🇪";
            case "NOK" -> "🇳🇴";
            case "DKK" -> "🇩🇰";
            case "ISK" -> "🇮🇸";
            case "ILS" -> "🇮🇱";
            case "AED" -> "🇦🇪";
            case "SAR" -> "🇸🇦";
            case "QAR" -> "🇶🇦";
            case "KWD" -> "🇰🇼";
            case "BHD" -> "🇧🇭";
            default -> regionalIndicator(currencyCode.substring(0, 2));
        };
    }

    private static String regionalIndicator(String iso2) {
        if (iso2 == null || iso2.length() != 2) return "🏳";
        StringBuilder out = new StringBuilder();
        for (char letter : iso2.toUpperCase().toCharArray()) {
            if (letter < 'A' || letter > 'Z') return "🏳";
            out.appendCodePoint(0x1F1E6 + (letter - 'A'));
        }
        return out.toString();
    }
}
