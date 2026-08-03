package de.noisebleed.dill;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BillExtractorTest {

    /** Grober Nachbau einer typischen GOÄ-Privatrechnung, wie die OCR sie liefert. */
    private static final String TYPICAL_BILL = String.join("\n",
            "Dr. med. Eva Musterfrau",
            "Fachärztin für Allgemeinmedizin",
            "Musterstraße 12",
            "91054 Erlangen",
            "",
            "Martin Fischer",
            "Beispielweg 3",
            "91052 Erlangen",
            "",
            "Rechnung Nr. 2026-0815",
            "Rechnungsdatum: 15.07.2026",
            "Behandlung vom 02.06.2026 bis 30.06.2026",
            "",
            "Ziffer 1 Beratung Faktor 2,3 10,72",
            "Ziffer 5 Untersuchung Faktor 2,3 10,72",
            "Ziffer 250 Blutentnahme Faktor 1,8 4,20",
            "",
            "Rechnungsbetrag: 25,64 €",
            "Zahlbar innerhalb von 30 Tagen.");

    @Test
    public void findsLabeledTotalInsteadOfLargestLineItem() {
        BillExtractor.Result r = BillExtractor.extract(TYPICAL_BILL);
        assertEquals(2564, r.amountCents);
    }

    @Test
    public void findsLabeledBillingDateInsteadOfTreatmentDates() {
        BillExtractor.Result r = BillExtractor.extract(TYPICAL_BILL);
        assertEquals("2026-07-15", r.billingDate);
    }

    @Test
    public void findsDoctorInLetterhead() {
        BillExtractor.Result r = BillExtractor.extract(TYPICAL_BILL);
        assertEquals("Dr. med. Eva Musterfrau", r.doctor);
    }

    @Test
    public void fallsBackToLargestAmountWithoutTotalLine() {
        BillExtractor.Result r = BillExtractor.extract("Position A 12,50\nPosition B 84,30\nPosition C 7,00");
        assertEquals(8430, r.amountCents);
    }

    @Test
    public void fallsBackToLatestDateWithoutLabel() {
        BillExtractor.Result r = BillExtractor.extract("Besuch am 03.02.2026\nBesuch am 17.02.2026");
        assertEquals("2026-02-17", r.billingDate);
    }

    @Test
    public void ignoresBirthDateLines() {
        BillExtractor.Result r = BillExtractor.extract(
                "Geburtsdatum: 24.12.1975\nRechnungsdatum: 01.03.2026\nBetrag 10,00");
        assertEquals("2026-03-01", r.billingDate);
    }

    @Test
    public void parsesThousandSeparator() {
        BillExtractor.Result r = BillExtractor.extract("Gesamtbetrag: 1.234,56 EUR");
        assertEquals(123456, r.amountCents);
    }

    @Test
    public void emptyTextYieldsEmptyResult() {
        BillExtractor.Result r = BillExtractor.extract("");
        assertEquals("", r.billingDate);
        assertEquals("", r.doctor);
        assertEquals(0, r.amountCents);
    }
}
