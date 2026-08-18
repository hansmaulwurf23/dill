package de.noisebleed.dill;

import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Detailansicht einer Rechnung: erkannte Daten prüfen und korrigieren, PDF ansehen, löschen. */
public class BillDetailActivity extends AppCompatActivity {

    public static final String EXTRA_BILL_ID = "billId";

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private BillStore store;
    private Bill bill;

    private TextInputEditText editDoctor;
    private TextInputEditText editDate;
    private TextInputEditText editAmount;
    private CheckBox checkPaid;
    private CheckBox checkHuk;
    private CheckBox checkBeihilfe;
    private CheckBox checkProcessed;

    /** Das gewählte Datum lebt hier, das EditText zeigt nur die formatierte Fassung an. */
    private LocalDate pickedDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        store = BillStore.get(this);
        bill = store.byId(getIntent().getStringExtra(EXTRA_BILL_ID));
        if (bill == null) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        editDoctor = findViewById(R.id.edit_doctor);
        editDate = findViewById(R.id.edit_date);
        editAmount = findViewById(R.id.edit_amount);
        checkPaid = findViewById(R.id.check_paid);
        checkHuk = findViewById(R.id.check_huk);
        checkBeihilfe = findViewById(R.id.check_beihilfe);
        checkProcessed = findViewById(R.id.check_processed);

        editDoctor.setText(bill.doctor);
        pickedDate = bill.billingDateAsLocalDate();
        if (pickedDate != null) {
            editDate.setText(GERMAN_DATE.format(pickedDate));
        }
        if (bill.amountCents > 0) {
            editAmount.setText(String.format(Locale.GERMANY, "%.2f", bill.amountCents / 100.0));
        }
        checkPaid.setChecked(bill.paid);
        checkHuk.setChecked(bill.sentHuk);
        checkBeihilfe.setChecked(bill.sentBeihilfe);
        checkProcessed.setChecked(bill.exported);

        // Das Datumsfeld ist nicht frei editierbar, ein Tipp öffnet den Kalender.
        editDate.setOnClickListener(v -> pickDate());

        TextView status = findViewById(R.id.detail_status);
        if (bill.exported) {
            String when = java.time.Instant.ofEpochMilli(bill.exportedAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(GERMAN_DATE);
            status.setText(getString(R.string.detail_status_exported, when));
        } else {
            status.setText(R.string.detail_status_new);
        }
        TextView scanned = findViewById(R.id.detail_scanned);
        scanned.setText(getString(R.string.detail_scanned_at, bill.displayCreatedAt()));

        Button openPdf = findViewById(R.id.button_open_pdf);
        openPdf.setOnClickListener(v -> openPdf());

        // Geteilt wird der gespeicherte Stand — ungespeicherte Änderungen im Formular würden
        // sonst stillschweigend fehlen, daher vorher speichern (ohne die Ansicht zu schließen).
        Button share = findViewById(R.id.button_share);
        share.setOnClickListener(v -> {
            applyEdits();
            store.save();
            ShareHelper.share(this, editDoctor, java.util.Collections.singletonList(bill));
        });
    }

    private void pickDate() {
        LocalDate initial = pickedDate != null ? pickedDate : LocalDate.now();
        new DatePickerDialog(this, (view, year, month, day) -> {
            pickedDate = LocalDate.of(year, month + 1, day);
            editDate.setText(GERMAN_DATE.format(pickedDate));
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
    }

    private void openPdf() {
        File pdf = store.pdfFile(bill);
        if (!pdf.exists()) {
            Snackbar.make(editDoctor, R.string.error_pdf_missing, Snackbar.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, "de.noisebleed.dill.fileprovider", pdf);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Snackbar.make(editDoctor, R.string.error_no_pdf_viewer, Snackbar.LENGTH_LONG).show();
        }
    }

    private void save() {
        applyEdits();
        store.save();
        finish();
    }

    private void applyEdits() {
        bill.doctor = editDoctor.getText() == null ? "" : editDoctor.getText().toString().trim();
        bill.billingDate = pickedDate == null ? "" : pickedDate.toString();
        bill.amountCents = parseAmountCents(editAmount.getText() == null ? "" : editAmount.getText().toString());
        bill.paid = checkPaid.isChecked();
        bill.sentHuk = checkHuk.isChecked();
        bill.sentBeihilfe = checkBeihilfe.isChecked();
        // Manuell umschaltbar, z. B. für importierte PDFs, die schon eingereicht sind — sie
        // sollen bei "Unverarbeitete exportieren/teilen" nicht wieder auftauchen.
        if (checkProcessed.isChecked() && !bill.exported) {
            bill.exported = true;
            bill.exportedAt = System.currentTimeMillis();
        } else if (!checkProcessed.isChecked()) {
            bill.exported = false;
            bill.exportedAt = 0;
        }
    }

    /** Akzeptiert "123,45", "123.45" und "123"; alles Unlesbare wird zu 0 (= unbekannt). */
    private static long parseAmountCents(String input) {
        String cleaned = input.trim().replace("€", "").replace("EUR", "").trim();
        if (cleaned.isEmpty()) {
            return 0;
        }
        cleaned = cleaned.replace(".", ",");
        try {
            String[] parts = cleaned.split(",");
            long euros = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0].replaceAll("\\s", ""));
            long cents = 0;
            if (parts.length > 1) {
                String c = parts[parts.length - 1];
                if (c.length() == 1) {
                    c = c + "0";
                }
                cents = Long.parseLong(c.substring(0, Math.min(2, c.length())));
            }
            return euros * 100 + cents;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete_confirm, (dialog, which) -> {
                    store.remove(bill);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_detail, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            save();
            return true;
        }
        if (id == R.id.action_delete) {
            confirmDelete();
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
