package de.noisebleed.dill;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.core.content.FileProvider;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Teilt Rechnungs-PDFs über das Android-Share-Sheet. Die Apps von HUK und Beihilfe Bayern
 * registrieren sich als Share-Ziel für PDFs (auch für mehrere auf einmal) und springen damit
 * direkt in ihren Einreichungs-Dialog — das ist der kürzeste Weg aus Dill heraus.
 *
 * Vor dem Teilen wird jede Datei mit sprechendem Namen in den Cache kopiert, damit beim
 * Empfänger nicht eine UUID als Dateiname ankommt.
 */
final class ShareHelper {

    private static final String TAG = "ShareHelper";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ShareHelper() {
    }

    static void share(Activity activity, View anchor, List<Bill> bills) {
        BillStore store = BillStore.get(activity);
        Handler main = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            File shareDir = new File(activity.getCacheDir(), "share");
            // Kopien vom letzten Teilen aufräumen — die Empfänger-App hat die Datei beim
            // Teilen sofort übernommen, danach liegt sie hier nur noch herum.
            File[] stale = shareDir.listFiles();
            if (stale != null) {
                for (File f : stale) {
                    if (!f.delete()) {
                        Log.w(TAG, "Kann alte Share-Kopie nicht löschen: " + f);
                    }
                }
            }
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                Log.e(TAG, "Kann Share-Verzeichnis nicht anlegen: " + shareDir);
            }

            ArrayList<Uri> uris = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            for (Bill bill : bills) {
                File source = store.pdfFile(bill);
                if (!source.exists()) {
                    continue;
                }
                String name = bill.exportFileName();
                // Gleicher Arzt, gleicher Tag, gleicher Betrag → Namen eindeutig machen.
                if (!usedNames.add(name)) {
                    name = name + "_" + bill.id.substring(0, 8);
                    usedNames.add(name);
                }
                File copy = new File(shareDir, name + ".pdf");
                try {
                    Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Log.e(TAG, "Kann Share-Kopie nicht anlegen: " + copy, e);
                    continue;
                }
                uris.add(FileProvider.getUriForFile(activity, "de.noisebleed.dill.fileprovider", copy));
            }

            main.post(() -> {
                if (uris.isEmpty()) {
                    Snackbar.make(anchor, R.string.error_pdf_missing, Snackbar.LENGTH_LONG).show();
                    return;
                }
                Intent send;
                if (uris.size() == 1) {
                    send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                send.setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(send,
                        activity.getString(R.string.share_chooser_title)));
            });
        });
    }
}
