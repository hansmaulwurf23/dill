package de.noisebleed.dill;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hält alle Rechnungen in einer einzelnen JSON-Datei im privaten App-Speicher. Für die erwartete
 * Größenordnung (ein paar Dutzend Rechnungen pro Jahr) reicht das völlig — eine Datenbank wäre
 * hier nur Ballast. Die PDFs liegen daneben unter files/bills/.
 */
public class BillStore {

    private static final String TAG = "BillStore";

    private static BillStore instance;

    private final File jsonFile;
    private final File pdfDir;
    private final List<Bill> bills = new ArrayList<>();

    public static synchronized BillStore get(Context context) {
        if (instance == null) {
            instance = new BillStore(context.getApplicationContext());
        }
        return instance;
    }

    private BillStore(Context context) {
        jsonFile = new File(context.getFilesDir(), "bills.json");
        pdfDir = new File(context.getFilesDir(), "bills");
        if (!pdfDir.exists() && !pdfDir.mkdirs()) {
            Log.e(TAG, "Kann PDF-Verzeichnis nicht anlegen: " + pdfDir);
        }
        load();
    }

    public File getPdfDir() {
        return pdfDir;
    }

    public File pdfFile(Bill bill) {
        return new File(pdfDir, bill.pdfFileName);
    }

    /** Kopie der Liste, neueste zuerst. */
    public synchronized List<Bill> getAll() {
        List<Bill> copy = new ArrayList<>(bills);
        copy.sort(Comparator.comparingLong((Bill b) -> b.createdAt).reversed());
        return copy;
    }

    public synchronized List<Bill> getUnexported() {
        List<Bill> result = new ArrayList<>();
        for (Bill b : getAll()) {
            if (!b.exported) {
                result.add(b);
            }
        }
        return result;
    }

    public synchronized Bill byId(String id) {
        for (Bill b : bills) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return null;
    }

    public synchronized void add(Bill bill) {
        bills.add(bill);
        save();
    }

    /** Die Bill-Objekte werden direkt mutiert, save() schreibt den Stand weg. */
    public synchronized void save() {
        JSONArray array = new JSONArray();
        try {
            for (Bill b : bills) {
                array.put(b.toJson());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Kann Rechnungen nicht serialisieren", e);
            return;
        }
        // Erst in eine Tempdatei, dann umbenennen — eine halb geschriebene bills.json würde
        // sonst bei einem Absturz alle Metadaten kosten.
        File tmp = new File(jsonFile.getParent(), jsonFile.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), array.toString(2).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(jsonFile)) {
                Log.e(TAG, "Kann bills.json nicht ersetzen");
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Kann bills.json nicht schreiben", e);
        }
    }

    public synchronized void remove(Bill bill) {
        bills.removeIf(b -> b.id.equals(bill.id));
        save();
        File pdf = pdfFile(bill);
        if (pdf.exists() && !pdf.delete()) {
            Log.w(TAG, "Kann PDF nicht löschen: " + pdf);
        }
    }

    private void load() {
        if (!jsonFile.exists()) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(content);
            for (int i = 0; i < array.length(); i++) {
                bills.add(Bill.fromJson(array.getJSONObject(i)));
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Kann bills.json nicht lesen", e);
        }
    }
}
