package de.noisebleed.dill;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verarbeitet das Ergebnis des ML-Kit-Dokumentenscanners: legt das aufbereitete PDF im privaten
 * App-Speicher ab, lässt die On-Device-Texterkennung über die Seitenbilder laufen und füllt die
 * Metadaten per {@link BillExtractor} vor. Die neue Rechnung startet immer als "unverarbeitet".
 */
public class ScanProcessor {

    private static final String TAG = "ScanProcessor";

    public interface Listener {
        void onDone(Bill bill);

        void onError(Exception e);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ScanProcessor() {
    }

    public static void process(Context context, GmsDocumentScanningResult result, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            Bill bill = new Bill();
            bill.id = UUID.randomUUID().toString();
            bill.createdAt = System.currentTimeMillis();
            bill.pdfFileName = bill.id + ".pdf";
            BillStore store = BillStore.get(app);

            // Ohne PDF gibt es nichts zu verwalten — das ist der einzige harte Fehlerfall.
            GmsDocumentScanningResult.Pdf pdf = result.getPdf();
            if (pdf == null) {
                main.post(() -> listener.onError(new IOException("Scanner hat kein PDF geliefert")));
                return;
            }
            try {
                copyToFile(app, pdf.getUri(), store.pdfFile(bill));
            } catch (IOException e) {
                main.post(() -> listener.onError(e));
                return;
            }

            // Texterkennung ist Kür: schlägt sie fehl, wird die Rechnung trotzdem gespeichert
            // und der Nutzer trägt die Daten von Hand nach.
            // Der ML-Kit-Callback landet auf dem Main-Thread — fürs Speichern zurück auf den
            // Executor, damit kein Datei-I/O den UI-Thread blockiert.
            recognizePages(app, result, text -> executor.execute(() -> {
                BillExtractor.Result extracted = BillExtractor.extract(text);
                bill.billingDate = extracted.billingDate;
                bill.doctor = extracted.doctor;
                bill.amountCents = extracted.amountCents;
                store.add(bill);
                main.post(() -> listener.onDone(bill));
            }));
        });
    }

    private interface TextCallback {
        void onText(String text);
    }

    private static void recognizePages(Context app, GmsDocumentScanningResult result, TextCallback callback) {
        List<GmsDocumentScanningResult.Page> pages = result.getPages();
        if (pages == null || pages.isEmpty()) {
            callback.onText("");
            return;
        }
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        List<Task<Text>> tasks = new ArrayList<>();
        for (GmsDocumentScanningResult.Page page : pages) {
            try {
                tasks.add(recognizer.process(InputImage.fromFilePath(app, page.getImageUri())));
            } catch (IOException e) {
                Log.w(TAG, "Kann Seitenbild nicht laden: " + page.getImageUri(), e);
            }
        }
        if (tasks.isEmpty()) {
            recognizer.close();
            callback.onText("");
            return;
        }
        Tasks.whenAllSuccess(tasks)
                .addOnSuccessListener(results -> {
                    StringBuilder text = new StringBuilder();
                    for (Object o : results) {
                        text.append(((Text) o).getText()).append('\n');
                    }
                    recognizer.close();
                    callback.onText(text.toString());
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Texterkennung fehlgeschlagen", e);
                    recognizer.close();
                    callback.onText("");
                });
    }

    private static void copyToFile(Context context, Uri source, File target) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("Kann Scan nicht öffnen: " + source);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
