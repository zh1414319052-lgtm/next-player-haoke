package dev.anilbeesetti.nextplayer.updates;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

import dev.anilbeesetti.nextplayer.core.ui.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Module boundary tests. MainActivity separately wires its drawer and dialog states. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateUiLifecycleTest {
    private static final class FakeBackend implements HaokeUpdateUi.Backend {
        int checks, cancels, closes;
        boolean lastManual;
        HaokeUpdateController.Listener pending;
        @Override public void check(boolean manual, HaokeUpdateController.Listener listener) {
            checks++; lastManual = manual; pending = listener;
        }
        @Override public void cancel() { cancels++; }
        @Override public void close() { closes++; }
        @Override public boolean isFresh(HaokeUpdateService.Result result) { return false; }
        @Override public boolean present(HaokeUpdateService.Result result, boolean manual, Runnable open) { return false; }
        @Override public boolean snooze() { return true; }
        @Override public boolean ignore(UpdateManifestValidator.Release release) { return true; }
    }

    public static class Screen extends Activity implements HaokeUpdateUi.Host {
        final FakeBackend backend = new FakeBackend();
        boolean focused = true, busy, modal, selecting, menuOpen;
        HaokeUpdateUi ui;
        @Override protected void attachBaseContext(Context base) { super.attachBaseContext(dev.anilbeesetti.nextplayer.core.common.PrimaryLanguage.INSTANCE.wrap(base)); }
        @Override protected void onCreate(Bundle state) {
            setTheme(dev.anilbeesetti.nextplayer.R.style.Theme_NextPlayer);
            super.onCreate(state);
            ui = new HaokeUpdateUi(this, this, backend);
        }
        @Override protected void onResume() { super.onResume(); ui.resume(); }
        @Override protected void onPause() { ui.pause(); super.onPause(); }
        @Override protected void onDestroy() { ui.close(); super.onDestroy(); }
        @Override public boolean isSafeForUpdates(boolean manual) {
            return focused && !busy && !modal && !selecting && !menuOpen;
        }
        @Override public void onDialogShown(AlertDialog dialog) { }
    }

    private void advance(long millis) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    @Test public void onlyIdleForegroundChecksAndPauseDiscardsLateManualError() {
        ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).setup().visible();
        Screen screen = controller.get();
        advance(400); assertEquals(1, screen.backend.checks);
        screen.focused = false; screen.ui.refresh(); advance(1000);
        assertEquals(1, screen.backend.checks);
        screen.focused = true; screen.ui.refresh(); advance(400);
        assertEquals(2, screen.backend.checks);
        screen.ui.manual(); assertTrue(screen.backend.lastManual);
        HaokeUpdateController.Listener late = screen.backend.pending;
        int cancellations = screen.backend.cancels;
        controller.pause(); assertTrue(screen.backend.cancels > cancellations);
        late.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNREGISTERED));
        advance(1000);
        assertTrue(ShadowDialog.getLatestDialog() == null || !ShadowDialog.getLatestDialog().isShowing());
        controller.stop().destroy(); assertEquals(1, screen.backend.closes);
    }

    @Test public void generationExportInputSelectionAndMenuPreventChecks() {
        ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).setup().visible();
        Screen screen = controller.get();
        screen.busy = true; screen.ui.refresh(); advance(400); screen.ui.manual();
        assertEquals(0, screen.backend.checks);
        screen.busy = false; screen.modal = true; screen.ui.refresh(); advance(400); screen.ui.manual();
        assertEquals(0, screen.backend.checks);
        screen.modal = false; screen.selecting = true; screen.ui.refresh(); advance(400);
        assertEquals(0, screen.backend.checks);
        screen.selecting = false; screen.menuOpen = true; screen.ui.refresh(); advance(400);
        assertEquals(0, screen.backend.checks);
        screen.menuOpen = false; screen.ui.refresh(); advance(400);
        assertEquals(1, screen.backend.checks);
        controller.pause().stop().destroy();
    }

    @Test public void unregisteredManualCheckNeverClaimsCurrentAndPauseClosesMessage() {
        ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).setup().visible();
        Screen screen = controller.get(); screen.ui.manual();
        screen.backend.pending.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNREGISTERED));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing());
        String message = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
        assertEquals(screen.getString(R.string.updates_unregistered), message);
        assertNotEquals(screen.getString(R.string.updates_current), message);
        controller.pause(); assertFalse(dialog.isShowing()); controller.stop().destroy();
    }

    @Test public void retriesAreBoundedAndCancelledWhenHostBecomesBusyOrPaused() {
        ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).setup().visible();
        Screen screen = controller.get();
        advance(400); assertEquals(1, screen.backend.checks);
        screen.backend.pending.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNAVAILABLE));
        advance(59999); assertEquals(1, screen.backend.checks);
        advance(1); assertEquals(2, screen.backend.checks);
        screen.backend.pending.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNAVAILABLE));
        advance(300000); assertEquals(3, screen.backend.checks);
        screen.backend.pending.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNAVAILABLE));
        advance(900000); assertEquals(3, screen.backend.checks);
        controller.pause().resume(); advance(400); assertEquals(4, screen.backend.checks);
        screen.backend.pending.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNAVAILABLE));
        screen.busy = true; screen.ui.refresh(); advance(60000); assertEquals(4, screen.backend.checks);
        screen.busy = false; screen.ui.refresh(); advance(400); assertEquals(5, screen.backend.checks);
        screen.backend.pending.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNAVAILABLE));
        controller.pause(); advance(300000); assertEquals(5, screen.backend.checks);
        controller.stop().destroy();
    }

    @Test public void lateResponseCannotInterruptNewTask() {
        ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).setup().visible();
        Screen screen = controller.get(); screen.ui.manual();
        HaokeUpdateController.Listener late = screen.backend.pending;
        screen.busy = true; screen.ui.refresh();
        late.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNREGISTERED));
        advance(1000);
        assertTrue(ShadowDialog.getLatestDialog() == null || !ShadowDialog.getLatestDialog().isShowing());
        controller.pause().stop().destroy();
    }
}
