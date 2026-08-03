package de.noisebleed.dill;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zieht per Heuristik Rechnungsdatum, Arzt und Betrag aus dem OCR-Text einer deutschen
 * Privatrechnung (GOÄ/GOZ). Heuristiken raten naturgemäß — das Ergebnis ist ein Vorschlag,
 * den der Nutzer in der Detailansicht korrigieren kann.
 */
public final class BillExtractor {

    public static class Result {
        /** ISO yyyy-MM-dd, leer wenn nicht gefunden. */
        public String billingDate = "";
        public String doctor = "";
        public long amountCents;
    }

    /** Beträge wie "123,45", "1.234,56", optional mit €/EUR dahinter. */
    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(?:\\.\\d{3})+|\\d+),(\\d{2})(?!\\d)");

    private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})\\b");

    /** Zeilen, auf denen der Endbetrag typischerweise steht. */
    private static final String[] TOTAL_KEYWORDS = {
            "rechnungsbetrag", "gesamtbetrag", "endbetrag", "endsumme", "zahlbetrag",
            "zu zahlen", "zu überweisen", "gesamtsumme", "gesamt", "summe",
    };

    private static final String[] DATE_KEYWORDS = {"rechnungsdatum", "rechnungs-datum", "datum"};

    /** Merkmale einer Arzt-/Praxiszeile im Briefkopf. */
    private static final Pattern DOCTOR_LINE = Pattern.compile(
            "(Dr\\.|Prof\\.|Praxis|Gemeinschaftspraxis|MVZ|Facharzt|Fachärztin|Arzt |Ärztin"
                    + "|Zahnarzt|Zahnärztin|Kieferorthopäd|Orthopäd|Augenarzt|Hautarzt"
                    + "|Physiotherap|Heilpraktik|Logopäd|Ergotherap)");

    private BillExtractor() {
    }

    public static Result extract(String text) {
        Result result = new Result();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String[] lines = text.split("\n");
        result.amountCents = findAmount(lines);
        LocalDate date = findDate(lines);
        if (date != null) {
            result.billingDate = date.toString();
        }
        result.doctor = findDoctor(lines);
        return result;
    }

    // ------------------------------------------------------------ Betrag

    private static long findAmount(String[] lines) {
        long keywordAmount = 0;
        long maxAmount = 0;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.GERMANY);
            boolean isTotalLine = false;
            for (String keyword : TOTAL_KEYWORDS) {
                if (lower.contains(keyword)) {
                    isTotalLine = true;
                    break;
                }
            }
            Matcher m = AMOUNT.matcher(line);
            while (m.find()) {
                long cents = parseAmountCents(m.group(1), m.group(2));
                maxAmount = Math.max(maxAmount, cents);
                if (isTotalLine) {
                    // Auf einer Summenzeile den größten Wert nehmen (falls z. B. Netto und
                    // Brutto nebeneinander stehen). Spätere Summenzeilen gewinnen — der
                    // Endbetrag steht auf der Rechnung üblicherweise zuletzt.
                    keywordAmount = Math.max(keywordAmount, cents);
                }
            }
        }
        // Ohne erkannte Summenzeile ist der größte Betrag auf der Rechnung der beste Tipp:
        // Einzelpositionen sind kleiner als die Endsumme.
        return keywordAmount > 0 ? keywordAmount : maxAmount;
    }

    private static long parseAmountCents(String euros, String cents) {
        try {
            long e = Long.parseLong(euros.replace(".", ""));
            return e * 100 + Long.parseLong(cents);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------ Datum

    private static LocalDate findDate(String[] lines) {
        LocalDate keywordDate = null;
        LocalDate latest = null;
        LocalDate today = LocalDate.now();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.GERMANY);
            // "Geburtsdatum" enthält "datum", meint aber den Patienten, nicht die Rechnung.
            boolean isDateLine = !lower.contains("geburt");
            if (isDateLine) {
                boolean hasKeyword = false;
                for (String keyword : DATE_KEYWORDS) {
                    if (lower.contains(keyword)) {
                        hasKeyword = true;
                        break;
                    }
                }
                isDateLine = hasKeyword;
            }
            Matcher m = DATE.matcher(line);
            while (m.find()) {
                LocalDate date = parseDate(m.group(1), m.group(2), m.group(3));
                if (date == null || date.isAfter(today)) {
                    continue;
                }
                if (isDateLine && keywordDate == null) {
                    keywordDate = date;
                }
                if (latest == null || date.isAfter(latest)) {
                    latest = date;
                }
            }
        }
        // Ohne beschriftetes Rechnungsdatum ist das jüngste Datum der beste Tipp — die
        // Behandlungsdaten liegen vor dem Rechnungsdatum.
        return keywordDate != null ? keywordDate : latest;
    }

    private static LocalDate parseDate(String day, String month, String year) {
        try {
            int y = Integer.parseInt(year);
            if (y < 100) {
                y += 2000;
            }
            return LocalDate.of(y, Integer.parseInt(month), Integer.parseInt(day));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ Arzt

    private static String findDoctor(String[] lines) {
        // Der Briefkopf steht oben auf der ersten Seite; weiter unten fangen wir uns nur noch
        // Bankverbindungen und Fußzeilen ein.
        int checked = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (++checked > 15) {
                break;
            }
            if (DOCTOR_LINE.matcher(trimmed).find()) {
                return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
            }
        }
        return "";
    }
}
