package de.noisebleed.dill;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Eine gescannte Arztrechnung samt Metadaten. Wird als JSON-Objekt in bills.json abgelegt. */
public class Bill {

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public String id;
    /** Dateiname des PDFs unterhalb von files/bills/ im privaten App-Speicher. */
    public String pdfFileName;
    /** Zeitpunkt des Scans, Epoch-Millis. */
    public long createdAt;
    /** Rechnungsdatum als ISO-String (yyyy-MM-dd), leer wenn (noch) unbekannt. */
    public String billingDate = "";
    /** Arzt bzw. Praxis, leer wenn (noch) unbekannt. */
    public String doctor = "";
    /** Rechnungsbetrag in Cent, 0 wenn (noch) unbekannt. */
    public long amountCents;
    /** true sobald das PDF in den Übergabeordner für HUK/Beihilfe kopiert wurde. */
    public boolean exported;
    public long exportedAt;
    /** Vom Nutzer gepflegt: Rechnung in der jeweiligen App eingereicht. */
    public boolean sentHuk;
    public boolean sentBeihilfe;

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("pdfFileName", pdfFileName);
        o.put("createdAt", createdAt);
        o.put("billingDate", billingDate);
        o.put("doctor", doctor);
        o.put("amountCents", amountCents);
        o.put("exported", exported);
        o.put("exportedAt", exportedAt);
        o.put("sentHuk", sentHuk);
        o.put("sentBeihilfe", sentBeihilfe);
        return o;
    }

    static Bill fromJson(JSONObject o) {
        Bill b = new Bill();
        b.id = o.optString("id");
        b.pdfFileName = o.optString("pdfFileName");
        b.createdAt = o.optLong("createdAt");
        b.billingDate = o.optString("billingDate");
        b.doctor = o.optString("doctor");
        b.amountCents = o.optLong("amountCents");
        b.exported = o.optBoolean("exported");
        b.exportedAt = o.optLong("exportedAt");
        b.sentHuk = o.optBoolean("sentHuk");
        b.sentBeihilfe = o.optBoolean("sentBeihilfe");
        return b;
    }

    // ------------------------------------------------------------ Anzeige-Helfer

    /** "123,45 €" oder null wenn unbekannt. */
    public String displayAmount() {
        if (amountCents <= 0) {
            return null;
        }
        return String.format(Locale.GERMANY, "%,.2f €", amountCents / 100.0);
    }

    /** "15.07.2026" oder null wenn unbekannt. */
    public String displayBillingDate() {
        LocalDate date = billingDateAsLocalDate();
        return date == null ? null : GERMAN_DATE.format(date);
    }

    public LocalDate billingDateAsLocalDate() {
        if (billingDate == null || billingDate.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(billingDate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public String displayCreatedAt() {
        return GERMAN_DATE.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /** Sprechender, sortierbarer Dateiname ohne Endung: "Arztrechnung_2026-07-15_Dr-med-Musterfrau_25-64-EUR". */
    public String exportFileName() {
        StringBuilder name = new StringBuilder("Arztrechnung_");
        name.append(billingDate.isEmpty() ? "unbekanntes-Datum" : billingDate);
        if (!doctor.isEmpty()) {
            name.append('_').append(sanitize(doctor));
        }
        if (amountCents > 0) {
            name.append('_').append(amountCents / 100).append('-')
                    .append(String.format(Locale.ROOT, "%02d", amountCents % 100)).append("-EUR");
        }
        return name.toString();
    }

    private static String sanitize(String s) {
        return s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
