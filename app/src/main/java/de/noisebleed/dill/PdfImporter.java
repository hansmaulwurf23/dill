package de.noisebleed.dill;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Importiert vorhandene PDFs (z. B. aus dem Übergabeordner) als neue Rechnungen. Die
 * Texterkennung liest nur Bilder, deshalb werden die ersten Seiten per {@link PdfRenderer}
 * gerastert und dann wie beim Scan die Metadaten vorgeschlagen. Importierte Rechnungen
 * starten als "unverarbeitet"; die Originaldatei bleibt unangetastet.
 */
final class PdfImporter {

    private static final String TAG = "PdfImporter";

    /** Datum, Arzt und Betrag stehen praktisch immer vorne — mehr Seiten kosten nur Zeit. */
    private static final int MAX_OCR_PAGES = 3;
    /** Reicht der OCR für A4-Rechnungen locker, hält die Bitmaps aber klein. */
    private static final int TARGET_WIDTH_PX = 1600;

    interface Listener {
        /** single ist gesetzt, wenn genau eine Datei gewählt und importiert wurde. */
        void onDone(int imported, int total, Bill single);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private PdfImporter() {
    }

    static void importPdfs(Context context, List<Uri> uris, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            BillStore store = BillStore.get(app);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            int imported = 0;
            Bill last = null;
            for (Uri uri : uris) {
                Bill bill = new Bill();
                bill.id = UUID.randomUUID().toString();
                bill.createdAt = System.currentTimeMillis();
                bill.pdfFileName = bill.id + ".pdf";
                try {
                    copyToFile(app, uri, store.pdfFile(bill));
                } catch (IOException | SecurityException e) {
                    Log.e(TAG, "Kann PDF nicht importieren: " + uri, e);
                    continue;
                }
                // OCR ist Kür — ein nicht lesbares PDF wird trotzdem übernommen.
                BillExtractor.Result extracted = BillExtractor.extract(
                        ocrFirstPages(recognizer, store.pdfFile(bill)));
                bill.billingDate = extracted.billingDate;
                bill.doctor = extracted.doctor;
                bill.amountCents = extracted.amountCents;
                store.add(bill);
                imported++;
                last = bill;
            }
            recognizer.close();
            final int count = imported;
            final Bill single = (uris.size() == 1 && imported == 1) ? last : null;
            main.post(() -> listener.onDone(count, uris.size(), single));
        });
    }

    private static String ocrFirstPages(TextRecognizer recognizer, File pdf) {
        StringBuilder text = new StringBuilder();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            int pages = Math.min(renderer.getPageCount(), MAX_OCR_PAGES);
            for (int i = 0; i < pages; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    float scale = Math.min(4f, (float) TARGET_WIDTH_PX / page.getWidth());
                    Bitmap bitmap = Bitmap.createBitmap(Math.round(page.getWidth() * scale),
                            Math.round(page.getHeight() * scale), Bitmap.Config.ARGB_8888);
                    // PDF-Seiten können transparenten Hintergrund haben — weiß vorbelegen,
                    // sonst rät die OCR auf Schwarz.
                    bitmap.eraseColor(0xFFFFFFFF);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    try {
                        // Wir sind auf dem Import-Executor, blockierendes Warten ist hier ok.
                        Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
                        text.append(result.getText()).append('\n');
                    } catch (ExecutionException | InterruptedException e) {
                        Log.w(TAG, "Texterkennung für Seite " + i + " fehlgeschlagen", e);
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    bitmap.recycle();
                }
            }
        } catch (IOException | SecurityException e) {
            // Passwortgeschützte oder kaputte PDFs: kein Text, Metadaten bleiben leer.
            Log.w(TAG, "Kann PDF nicht rastern: " + pdf, e);
        }
        return text.toString();
    }

    private static void copyToFile(Context context, Uri source, File target) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("Kann Quelle nicht öffnen: " + source);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
