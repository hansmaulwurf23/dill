package de.noisebleed.dill;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "dill";
    private static final String PREF_EXPORT_TREE = "exportTreeUri";

    private BillStore store;
    private BillAdapter adapter;
    private TextView emptyView;
    private RecyclerView list;
    private FloatingActionButton scanButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Snackbar processingSnackbar;

    private final ActivityResultLauncher<IntentSenderRequest> scanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                GmsDocumentScanningResult scan =
                        GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
                if (result.getResultCode() != RESULT_OK || scan == null) {
                    return;
                }
                onScanFinished(scan);
            });

    private final ActivityResultLauncher<String[]> importPicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) {
                    return;
                }
                importPdfs(uris);
            });

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) {
                    return;
                }
                // Dauerhaft merken, damit der Ordner nicht bei jedem Export neu gewählt werden muss.
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs().edit().putString(PREF_EXPORT_TREE, uri.toString()).apply();
                exportUnprocessed();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        store = BillStore.get(this);

        emptyView = findViewById(R.id.empty_view);
        list = findViewById(R.id.bill_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BillAdapter(bill -> {
            Intent intent = new Intent(this, BillDetailActivity.class);
            intent.putExtra(BillDetailActivity.EXTRA_BILL_ID, bill.id);
            startActivity(intent);
        });
        list.setAdapter(adapter);

        scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(v -> startScan());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<Bill> bills = store.getAll();
        adapter.setBills(bills);
        emptyView.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(bills.isEmpty() ? View.GONE : View.VISIBLE);
        updateSummary(bills);
    }

    /** Toolbar-Untertitel: Anzahl und Summe der offenen (unverarbeiteten) Rechnungen. */
    private void updateSummary(List<Bill> bills) {
        if (getSupportActionBar() == null) {
            return;
        }
        int open = 0;
        long sumCents = 0;
        boolean sumIncomplete = false;
        for (Bill b : bills) {
            if (b.exported) {
                continue;
            }
            open++;
            if (b.amountCents > 0) {
                sumCents += b.amountCents;
            } else {
                sumIncomplete = true;
            }
        }
        if (open == 0) {
            getSupportActionBar().setSubtitle(null);
            return;
        }
        String sum = String.format(java.util.Locale.GERMANY, "%,.2f €", sumCents / 100.0);
        if (sumIncomplete) {
            // Mindestens eine offene Rechnung ohne erkannten Betrag — die Summe ist unvollständig.
            sum = getString(R.string.subtitle_sum_at_least, sum);
        }
        getSupportActionBar().setSubtitle(
                getResources().getQuantityString(R.plurals.subtitle_open, open, open, sum));
    }

    // ---------------------------------------------------------------- Scannen

    private void startScan() {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(10)
                .setResultFormats(
                        GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                        GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                // FULL: mit automatischer Bildaufbereitung (Zuschnitt, Entzerrung, Filter).
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build();
        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);
        scanner.getStartScanIntent(this)
                .addOnSuccessListener(intentSender ->
                        scanLauncher.launch(new IntentSenderRequest.Builder(intentSender).build()))
                .addOnFailureListener(e ->
                        Snackbar.make(scanButton, R.string.error_scanner_unavailable, Snackbar.LENGTH_LONG).show());
    }

    private void onScanFinished(GmsDocumentScanningResult scan) {
        processingSnackbar = Snackbar.make(scanButton, R.string.hint_processing, Snackbar.LENGTH_INDEFINITE);
        processingSnackbar.show();
        ScanProcessor.process(this, scan, new ScanProcessor.Listener() {
            @Override
            public void onDone(Bill bill) {
                dismissProcessing();
                refreshList();
                // Direkt in die Detailansicht: die erkannten Daten sind nur ein Vorschlag und
                // wollen kontrolliert werden.
                Intent intent = new Intent(MainActivity.this, BillDetailActivity.class);
                intent.putExtra(BillDetailActivity.EXTRA_BILL_ID, bill.id);
                startActivity(intent);
            }

            @Override
            public void onError(Exception e) {
                dismissProcessing();
                Snackbar.make(scanButton, R.string.error_processing, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void dismissProcessing() {
        if (processingSnackbar != null) {
            processingSnackbar.dismiss();
            processingSnackbar = null;
        }
    }

    // ---------------------------------------------------------------- Import

    /**
     * Übernimmt bereits vorhandene PDFs (z. B. aus Downloads/Arztrechnungen) als neue,
     * unverarbeitete Rechnungen. Die Originaldateien bleiben liegen.
     */
    private void importPdfs(List<Uri> uris) {
        processingSnackbar = Snackbar.make(scanButton, R.string.hint_importing, Snackbar.LENGTH_INDEFINITE);
        processingSnackbar.show();
        PdfImporter.importPdfs(this, uris, (imported, total, single) -> {
            dismissProcessing();
            refreshList();
            if (single != null) {
                // Wie nach dem Scan: die erkannten Daten wollen kontrolliert werden.
                Intent intent = new Intent(this, BillDetailActivity.class);
                intent.putExtra(BillDetailActivity.EXTRA_BILL_ID, single.id);
                startActivity(intent);
                return;
            }
            String message = imported == total
                    ? getResources().getQuantityString(R.plurals.hint_imported, imported, imported)
                    : getString(R.string.error_import_partial, imported, total);
            Snackbar.make(scanButton, message, Snackbar.LENGTH_LONG).show();
        });
    }

    // ---------------------------------------------------------------- Export

    /**
     * Kopiert alle unverarbeiteten PDFs in den gewählten Übergabeordner (z. B. Downloads/
     * Arztrechnungen). Von dort können die Apps von HUK und Beihilfe sie über ihren Dateiwähler
     * einlesen. Danach gelten die Rechnungen hier als exportiert.
     */
    private void exportUnprocessed() {
        List<Bill> toExport = store.getUnexported();
        if (toExport.isEmpty()) {
            Snackbar.make(scanButton, R.string.hint_nothing_to_export, Snackbar.LENGTH_SHORT).show();
            return;
        }
        DocumentFile dir = exportDir();
        if (dir == null) {
            folderPicker.launch(null);
            return;
        }
        Snackbar.make(scanButton, R.string.hint_exporting, Snackbar.LENGTH_SHORT).show();
        executor.execute(() -> {
            int copied = 0;
            for (Bill bill : toExport) {
                if (copyToExportDir(bill, dir)) {
                    bill.exported = true;
                    bill.exportedAt = System.currentTimeMillis();
                    copied++;
                }
            }
            store.save();
            final int count = copied;
            main.post(() -> {
                refreshList();
                String message = count == toExport.size()
                        ? getResources().getQuantityString(R.plurals.hint_exported, count, count)
                        : getString(R.string.error_export_partial, count, toExport.size());
                Snackbar.make(scanButton, message, Snackbar.LENGTH_LONG).show();
            });
        });
    }

    private boolean copyToExportDir(Bill bill, DocumentFile dir) {
        String name = bill.exportFileName();
        // Namenskollision (gleicher Arzt, gleicher Tag, gleicher Betrag) nicht überschreiben.
        if (dir.findFile(name + ".pdf") != null) {
            name = name + "_" + bill.id.substring(0, 8);
        }
        DocumentFile target = dir.createFile("application/pdf", name + ".pdf");
        if (target == null) {
            return false;
        }
        try (InputStream in = new FileInputStream(store.pdfFile(bill));
             OutputStream out = getContentResolver().openOutputStream(target.getUri())) {
            if (out == null) {
                return false;
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Teilt alle unverarbeiteten PDFs über das Share-Sheet — die Apps von HUK und Beihilfe
     * nehmen sie dort direkt in ihren Einreichungs-Dialog. Der Status bleibt unverändert:
     * ob eingereicht wurde, weiß nur der Nutzer und pflegt es per Häkchen in der Detailansicht.
     */
    private void shareUnprocessed() {
        List<Bill> toShare = store.getUnexported();
        if (toShare.isEmpty()) {
            Snackbar.make(scanButton, R.string.hint_nothing_to_export, Snackbar.LENGTH_SHORT).show();
            return;
        }
        ShareHelper.share(this, scanButton, toShare);
    }

    /** Der gemerkte Übergabeordner, oder null wenn keiner (mehr) beschreibbar ist. */
    private DocumentFile exportDir() {
        String saved = prefs().getString(PREF_EXPORT_TREE, null);
        if (saved == null) {
            return null;
        }
        DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(saved));
        // Die Berechtigung kann verschwinden, z. B. wenn der Ordner gelöscht wurde.
        if (dir == null || !dir.canWrite()) {
            return null;
        }
        return dir;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_share_all) {
            shareUnprocessed();
            return true;
        }
        if (id == R.id.action_export) {
            exportUnprocessed();
            return true;
        }
        if (id == R.id.action_import) {
            importPicker.launch(new String[]{"application/pdf"});
            return true;
        }
        if (id == R.id.action_pick_folder) {
            folderPicker.launch(null);
            return true;
        }
        if (id == R.id.about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {
        View content = getLayoutInflater().inflate(R.layout.dialog_about, null);
        TextView version = content.findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA));
        new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
