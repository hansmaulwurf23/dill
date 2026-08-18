package de.noisebleed.dill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class BillTest {

    /**
     * Bestandsdaten aus Versionen ohne "paid"-Feld müssen unverändert laden — die App ist
     * produktiv im Einsatz, ein Schemawechsel darf keine Daten kosten.
     */
    @Test
    public void loadsLegacyJsonWithoutPaidField() throws Exception {
        JSONObject legacy = new JSONObject();
        legacy.put("id", "abc");
        legacy.put("pdfFileName", "abc.pdf");
        legacy.put("createdAt", 123L);
        legacy.put("billingDate", "2026-07-15");
        legacy.put("doctor", "Dr. Muster");
        legacy.put("amountCents", 2564L);
        legacy.put("exported", true);
        legacy.put("exportedAt", 456L);
        legacy.put("sentHuk", true);
        legacy.put("sentBeihilfe", false);

        Bill b = Bill.fromJson(legacy);

        assertEquals("abc", b.id);
        assertEquals("2026-07-15", b.billingDate);
        assertEquals(2564, b.amountCents);
        assertTrue(b.exported);
        assertTrue(b.sentHuk);
        assertFalse(b.sentBeihilfe);
        assertFalse("fehlendes Feld muss als 'nicht bezahlt' laden", b.paid);
    }

    @Test
    public void jsonRoundtripPreservesAllFields() throws Exception {
        Bill b = new Bill();
        b.id = "xyz";
        b.pdfFileName = "xyz.pdf";
        b.createdAt = 111L;
        b.billingDate = "2026-08-01";
        b.doctor = "Praxis Beispiel";
        b.amountCents = 999;
        b.exported = true;
        b.exportedAt = 222L;
        b.sentHuk = true;
        b.sentBeihilfe = true;
        b.paid = true;

        Bill copy = Bill.fromJson(new JSONObject(b.toJson().toString()));

        assertEquals(b.id, copy.id);
        assertEquals(b.pdfFileName, copy.pdfFileName);
        assertEquals(b.createdAt, copy.createdAt);
        assertEquals(b.billingDate, copy.billingDate);
        assertEquals(b.doctor, copy.doctor);
        assertEquals(b.amountCents, copy.amountCents);
        assertEquals(b.exported, copy.exported);
        assertEquals(b.exportedAt, copy.exportedAt);
        assertEquals(b.sentHuk, copy.sentHuk);
        assertEquals(b.sentBeihilfe, copy.sentBeihilfe);
        assertEquals(b.paid, copy.paid);
    }
}
