package com.slingring;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Desktop window for SlingRing: one process, one JVM.
 *
 *   init()   starts the whole backend (services, PeerServer, mDNS, UiApiServer on 127.0.0.1)
 *   start()  opens a window containing a WebView that loads the UI from that local server
 *   stop()   shuts the backend down when the window closes
 * 
 * Run through Launcher (a plain main class) when building a fat jar.
 */
public class DesktopApp extends Application {

    // Load "/" (not /api/home): the server serves the page there, so relative links such as
    // app.js and styles.css resolve to /app.js and /styles.css, which it maps to /ui/...
    private static final String HOME_PATH = "/";

    // Pure ASCII output, so the JSON is always a safe JavaScript literal.
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build());

    /** Sends the page's console output through alert(), which we catch with setOnAlert. */
    private static final String FORWARD_CONSOLE = """
            (function () {
              ['log', 'info', 'warn', 'error'].forEach(function (level) {
                var original = console[level];
                console[level] = function () {
                  try { alert('[' + level + '] ' + Array.prototype.map.call(arguments, String).join(' ')); } catch (e) {}
                  if (original) original.apply(console, arguments);
                };
              });
              window.addEventListener('error', function (e) {
                try { alert('[error] ' + e.message + ' (' + e.filename + ':' + e.lineno + ')'); } catch (x) {}
              });
            })();
            """;

    private App backend;

    @Override
    public void init() throws Exception {
        // Runs on the JavaFX launcher thread, before any window exists.
        backend = new App(App.resolveHome());
        backend.start();
    }

    @Override
    public void start(Stage stage) {
        String origin = "http://127.0.0.1:" + backend.getUiPort();

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        WebEngine engine = view.getEngine();

        // Only ever show our own page.
        engine.setCreatePopupHandler(config -> null);   // refuse window.open and target=_blank
        engine.locationProperty().addListener((obs, old, location) -> {
            boolean ours = location == null || location.isEmpty() || location.startsWith(origin + "/");
            if (!ours) {
                Platform.runLater(() -> engine.load(origin + HOME_PATH));
            }
        });

        // Diagnostics: no developer tools in a WebView, so send page problems to stderr.
        engine.setOnError(e -> System.err.println("WebView error: " + e.getMessage()));
        engine.setOnAlert(e -> System.err.println("[page] " + clean(e.getData())));
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                engine.executeScript(FORWARD_CONSOLE);
            }
        });

        // Dropped files. Event filters run before the WebView's own drag handling.
        view.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            }
        });
        view.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            Dragboard board = e.getDragboard();
            if (board.hasFiles()) {
                filesDropped(engine, board.getFiles());
                e.setDropCompleted(true);
                e.consume();
            }
        });

        stage.setTitle("SlingRing");
        stage.setScene(new Scene(view, 1000, 700));
        stage.setMinWidth(700);
        stage.setMinHeight(450);
        engine.load(origin + HOME_PATH);
        stage.show();
    }

    @Override
    public void stop() {
        if (backend != null) {
            backend.stop();
        }
    }

    /** Calls window.onFilesDropped(paths) in the page, if the page defines it. */
    private static void filesDropped(WebEngine engine, List<File> files) {
        try {
            String paths = JSON.writeValueAsString(files.stream().map(File::getAbsolutePath).toList());
            engine.executeScript("window.onFilesDropped && window.onFilesDropped(" + paths + ")");
        } catch (JsonProcessingException e) {
            System.err.println("Could not pass dropped files to the page: " + e);
        }
    }

    private static String clean(String text) {
        String safe = String.valueOf(text).replaceAll("\\p{Cntrl}", " ");
        return safe.length() > 500 ? safe.substring(0, 500) + "..." : safe;
    }
}