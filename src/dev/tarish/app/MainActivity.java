package dev.tarish.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.tarish.TarishPeer;
import dev.tarish.TarishStatus;
import dev.tarish.ITarishCallback;
import dev.tarish.ITarishService;
import dev.tarish.TarishGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tarish: one screen, two modes.
 *
 * <p>Receive and Send are modes of the same screen rather than separate activities. The
 * identity, the service connection and the transfer state are shared between them, so
 * switching should not throw any of it away — and sending should feel like the same
 * tool, not a different app.
 *
 * <p><b>Visibility follows this screen.</b> Being in Receive mode with the screen in
 * front of the user turns discovery on; leaving turns it off. Nothing advertises from
 * boot. That is a privacy position rather than a limitation, and it also stands in for
 * the per-transfer prompt that does not exist yet: while there is no prompt, closing the
 * app is the only "no", so it has to be a real one.
 */
public final class MainActivity extends Activity implements BottomNav.Listener {

    private static final String TAG = "TarishUI";
    private static final String SERVICE_NAME = "dev.tarish.ITarishService/default";

    /** One visible session. The daemon expires it on its own timer regardless. */
    private static final int VISIBLE_SECONDS = 600;
    private static final long POLL_MS = 1500;
    /** Renew well inside the daemon's expiry, so there is no window where it has lapsed. */
    // (auto-renew removed — the visible window now expires and is re-enabled deliberately)
    private static final int REQ_PICK = 1;

    // Outcomes from ITarishCallback.onTransferFinished.
    private static final int STATUS_FAILED = -1;
    private static final int STATUS_DECLINED = -2;
    private static final int STATUS_CANCELLED = -3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> shared = new ArrayList<>();

    private ITarishService service;
    private BottomNav nav;
    private LinearLayout content;
    private boolean sendMode;
    /** User choice merged with managed configuration; pushed to the daemon on connect. */
    private PolicyStore policyStore;
    private dev.tarish.TarishPolicy policy;
    private long activeTransfer;

    /**
     * The radios Tarish needs, and whether we were the ones who switched them on.
     *
     * AWDL cannot start with Wi-Fi off -- not "works badly", cannot start: every mode and
     * channel is refused, because the AWDL driver rides the Wi-Fi driver's interface. A
     * user with Wi-Fi off saw an app that never found anybody and no explanation, which is
     * what was reported. See Radios.
     */
    private Radios radios;
    /** Hosts the Wi-Fi Direct group an inbound transfer asks for. See onGroupNeeded. */
    private final WifiDirectHost wifiDirectHost = new WifiDirectHost();

    /** Set once per foreground visit, so a declined prompt is not asked again immediately. */
    private boolean askedAboutRadios;

    /**
     * Deferred restore. Same reasoning as the AWDL radio gate: leaving the app for a file
     * picker or a glance at a notification should not cycle the user's Wi-Fi. Only a real
     * departure does, and never mid-transfer.
     */
    private static final long RADIO_RESTORE_DELAY_MS = 30_000L;

    private final Runnable restoreRadios = new Runnable() {
        @Override
        public void run() {
            if (activeTransfer != 0) {
                // A transfer outlives the foreground. Try again once it is done rather
                // than pulling the radio out from under it.
                main.postDelayed(this, RADIO_RESTORE_DELAY_MS);
                return;
            }
            radios.restore();
        }
    };
    /**
     * What we last asked the daemon for.
     *
     * render() rebuilds every view, so the strip has to be able to re-state the current
     * condition rather than starting from a hardcoded default. Without this, switching
     * to Send and back showed "not visible" while the device was still advertising --
     * the label was a fresh view that nobody had told.
     */
    private boolean discoverable;
    /** The beacon subtitle that shows the visible-for countdown; updated each poll tick. */
    private TextView visibleCountdownView;
    /** Last seen link state, so the poll tick can redraw when it CHANGES. */
    private boolean lastTransportUp;
    /**
     * Did the hero's state branch say "ready"? The countdown shares the subtitle with every
     * other state, so only the branch that OWNS the line may let the tick refine it.
     */
    private boolean heroReady;
    /** A prompt is on screen. Guards against stacking a second BiometricPrompt. */
    private boolean authPromptUp;
    /**
     * What the peer list currently shows.
     *
     * The list used to be torn down and rebuilt on every poll, twice a second. A tap
     * that landed between removeAllViews() and the re-add hit a view that had already
     * been discarded, so tapping a device did nothing at all -- intermittently, which
     * made it look like the send was failing rather than never starting.
     */
    private String peerSignature;
    /** When visibility was last asserted, so it can be renewed before the daemon expires it. */
    private long visibleSince;
    /** Which protocol the pending offer arrived over; one of ITarishService.PROTOCOL_*. */
    private int offerProtocol;
    /** The offer waiting for an answer, or null. Set by onTransferOffered. */
    private long offerId;
    private String offerFrom;
    private String[] offerNames = new String[0];
    /** Total bytes the offer declared, for the transfer card's "of N" and ETA. */
    private long offerBytes;

    private String outcomeTitle;
    private String outcomeDetail;

    // Receive-mode views, rebuilt whenever the mode changes.
    private LinearLayout identity;
    private LinearLayout inbox;
    private TextView inboxLabel;
    private TextView deviceLine;
    private TextView stateLine;
    private PulseView liveDot;
    // Every transfer, in both directions and every outcome -- received, sent, cancelled,
    // declined, failed -- newest last. The screen's "Recent" list reads from this.
    private final List<TransferRecord> activity = new ArrayList<>();
    private LinearLayout peerBox;
    private android.app.AlertDialog pinDialog;
    /** Receiver-side: shows the session code to read out to the sender. */
    private android.app.AlertDialog pinShowDialog;
    /**
     * Receiver-side: the session code, held until the person ACCEPTS. The daemon derives and
     * sends it with the offer (before any answer), but showing it then put a code on screen
     * over the Accept/Decline card -- codes before consent. Shown once the offer is accepted,
     * which is also when the sender starts asking for it.
     */
    private String pendingReceiverPin;
    private TextView pinMessage;
    private EditText pinEntry;
    private long pinTransfer;
    /// Set when the person cancels at the PIN prompt, so the outcome reads "Cancelled"
    /// rather than "Declined" -- which would blame the other device for our own choice.
    private boolean pinCancelled;
    // The big top area is a single STAGE that morphs between waiting, an offer, a live
    // transfer and a completion flash -- rather than scattering those across separate cards.
    private enum Stage { IDLE, OFFER, TRANSFER, DONE }
    private Stage stage = Stage.IDLE;

    private TextView progressLabel;
    private RingView progressRing;
    // The live transfer view's parts: who, what, a state word, and the ring around the
    // file's icon. Populated when the stage is TRANSFER, updated in place by updateProgress.
    private TextView progressPeer;
    private TextView progressBadge;
    private TextView progressState;
    private TextView progressWhat;
    private android.widget.FrameLayout progressIcon;
    // The completion flash's content, held while the stage is DONE.
    private String stageDoneWord;
    private boolean stageDoneIncoming;
    // The docked "recent" panel at the bottom, rebuilt per render.
    private LinearLayout dock;
    // Bumped whenever a transfer begins or a "Done" flash is scheduled, so a delayed hide
    // only fires if nothing newer has taken the card over in the meantime.
    private long xferToken;
    // Speed/ETA tracking for the active transfer. Rate is a smoothed bytes/sec so the
    // number does not jitter every tick; times are elapsedRealtime millis.
    private boolean xferSending;
    // The active transfer's context, kept so an outcome (sent/cancelled/failed) can be
    // recorded with who and what even though onTransferFinished carries only an id.
    private String xferPeerName;
    private int xferProtocol;
    private String[] xferNames = new String[0];
    private long xferStartMs;
    private long xferLastMs;
    private long xferLastBytes;
    private double xferRate;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            // Keep trying to get back to the daemon. This is the only recovery path
            // that works for a service which restarts: the proxy is dead for good, and
            // a client that does not rebind sits there looking alive and doing nothing
            // until someone force-stops it.
            if (service == null) {
                if (connect()) {
                    Log.i(TAG, "reconnected to the daemon");
                    setDiscoverable(!sendMode, "reconnect");
                    render();
                }
                main.postDelayed(this, POLL_MS);
                return;
            }
            // THE LINK COMING UP MUST REDRAW THE SCREEN.
            //
            // tarishd brings AWDL up a few seconds after boot, and the receive screen is
            // usually drawn BEFORE that. Nothing re-rendered when the link arrived, so the
            // hero sat on "Cannot receive" until the person happened to switch tabs -- on
            // every boot, on a device that was in fact ready. Seen on 2026092323: the daemon
            // logging "AirDrop server up" while the screen still said it could not receive.
            //
            // Cheap: getStatus() is one binder call already made by transportUp(), and this
            // only re-renders on a CHANGE, not every tick.
            boolean up = transportUp();
            if (up != lastTransportUp) {
                lastTransportUp = up;
                Log.i(TAG, "transport " + (up ? "up" : "down") + " — redrawing");
                render();
            }
            // RELOCK ON EXPIRY, while the app is open and being looked at. Without this
            // the window ends silently and the screen keeps showing peers and the inbox
            // until something else happens to redraw -- which is precisely the state the
            // lock exists to prevent.
            if (!AuthWindow.isOpen()) {
                Log.i(TAG, "authenticated window ended — relocking");
                render();
                main.postDelayed(this, POLL_MS);
                return;
            }
            if (sendMode) {
                refreshPeers();
            } else if (discoverable) {
                // A visible ten-minute window that actually expires, rather than silently
                // renewing forever. The daemon stops advertising on its own timer; when the
                // window elapses we reflect that and offer re-enable (see buildIdentityStrip),
                // so a person can see how long they are visible and re-arm deliberately.
                long remainingMs = (long) VISIBLE_SECONDS * 1000L
                        - (System.currentTimeMillis() - visibleSince);
                if (remainingMs <= 0) {
                    setDiscoverable(false, "visible window expired");
                    render();
                } else {
                    updateVisibleCountdown(remainingMs);
                }
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    private final ITarishCallback callback = new ITarishCallback.Stub() {
        @Override public void onPeerFound(TarishPeer peer) {}
        @Override public void onPeerLost(String peerId) {}

        @Override
        public void onTransferOffered(long id, String peer, String[] names, long bytes,
                int protocol) {
            main.post(() -> {
                // Do NOT start progress here. Nothing is being received yet -- the
                // daemon is holding the connection open waiting for this answer, and
                // showing a progress bar for a transfer nobody has agreed to was how
                // the old auto-accept looked from the outside.
                // Never prompt for a transfer WE started.
                //
                // The daemon no longer raises an offer on the send path, which is what
                // made tapping a peer put an Accept/Decline card on the sending phone.
                // This is the second lock on that door: the two callbacks now mean
                // opposite things, and confusing them again would look exactly like a
                // device asking itself for permission.
                if (id == activeTransfer) {
                    Log.w(TAG, "ignoring an offer for our own transfer " + id);
                    return;
                }
                offerId = id;
                offerFrom = (peer == null || peer.isEmpty()) ? "A nearby device" : peer;
                offerNames = names == null ? new String[0] : names;
                offerBytes = bytes;
                offerProtocol = protocol;
                // ENTER THE OFFER STAGE. buildReceive() only draws the accept card while
                // stage == Stage.OFFER, so without this the offer arrives, offerId is set,
                // and nothing shows — the daemon then refuses it as unanswered after 45s.
                // (The stage-UI redesign added this gate and never set the stage here.)
                stage = Stage.OFFER;
                // A genuine incoming request the person may not be looking at — buzz for it.
                hapticOffer();
                if (sendMode) {
                    // An offer arrives on the receive side by definition. Switch, or
                    // the prompt would be built into a screen nobody is looking at.
                    sendMode = false;
                }
                render();
            });
        }

        @Override
        public void onTransferProgress(long id, long done, long total) {
            main.post(() -> {
                // ADOPT A TRANSFER NOBODY ANNOUNCED.
                //
                // With require_confirmation off the daemon accepts without asking, so
                // there is no onTransferOffered and activeTransfer is still 0. Progress
                // for an unknown id used to be dropped here, which made an auto-accepted
                // receive completely silent: no progress, no sign of anything happening,
                // and the file simply appeared in the inbox when it was already done.
                //
                // Bytes arriving ARE the announcement in that case. Only while RECEIVING:
                // an unknown id during a send is not ours to adopt, and taking it over
                // would point the progress bar at someone else's transfer.
                if (id != activeTransfer) {
                    if (activeTransfer == 0 && !sendMode) {
                        activeTransfer = id;
                    } else {
                        return;
                    }
                }
                updateProgress(done, total);
            });
        }

        @Override
        public void onTransferPinRequired(long id) {
            // PIN VERIFICATION INTENTIONALLY DISABLED — see docs/PIN-DISABLED.md. The daemon no
            // longer raises this; the sender no longer prompts for a PIN. No-op defensively.
            //   main.post(() -> { if (id == activeTransfer) askForPin(id); });
        }

        @Override
        public void onTransferPinDisplay(long id, String pin) {
            // PIN VERIFICATION INTENTIONALLY DISABLED — see docs/PIN-DISABLED.md. The daemon no
            // longer derives or sends a PIN; the receiver shows none. No-op defensively.
            //   main.post(() -> pendingReceiverPin = pin);
        }

        /**
         * Deliberately empty -- TransferService does this one.
         *
         * Both components are registered during a transfer, and joining a Wi-Fi Direct
         * group twice for one transfer would have the second attempt tear down the first's
         * group. The service is the right owner because it is the half that survives this
         * activity going away, which is what the wait for a group join invites.
         */
        @Override
        public void onUpgradeNeeded(long id, dev.tarish.TarishUpgrade upgrade) {
        }

        /** Also TransferService's, for the same reason as onUpgradeNeeded. */
        @Override
        public void onGroupNeeded(long id) {
            // ANSWER, ALWAYS. This was an empty stub, and silence is not free.
            //
            // Receiving off-network makes this device the ADVERTISER, and the advertiser
            // hosts the Wi-Fi Direct group — the discoverer only joins. So an inbound
            // transfer that wants to stop crawling over Bluetooth parks here waiting for
            // us, and an empty body means it waits the whole timeout and then asks again.
            //
            // Measured with both phones off-network: the daemon logged "no client answered
            // the group request; staying put" every 30s and the payload never moved at
            // all. Not slow — nothing. The interface says so in as many words: "ANSWER
            // EITHER WAY. The inbound transfer is parked waiting for this, and a client
            // that simply does not reply costs it the timeout before it carries on."
            //
            // TransferService also implements this and is the right owner once a transfer
            // has a notification, but it answers only for the id it is tracking and
            // returns silently otherwise — so on the receive path nobody was answering.
            //
            // Off the main thread: forming a group is 4-8s on real hardware and slower on
            // a cold driver, and this is a binder callback.
            ITarishService svc = service;
            if (svc == null) {
                return;
            }
            new Thread(() -> {
                TarishGroup group = null;
                try {
                    group = wifiDirectHost.create(MainActivity.this);
                } catch (Throwable t) {
                    Log.w(TAG, "could not host a Wi-Fi Direct group", t);
                }
                try {
                    // null is a real answer, and the one that matters here: it tells the
                    // daemon to stop waiting and carry on over Bluetooth immediately.
                    svc.provideWifiDirectGroup(id, group);
                } catch (Exception e) {
                    Log.w(TAG, "could not answer the group request", e);
                }
            }, "tarish-group-host").start();
        }

        @Override
        public void onTransferFinished(long id, int status) {
            // A GROUP LEFT UP HOLDS THE RADIO, and keeps the device on a network that
            // exists for nobody. The interface is explicit that teardown is the client's,
            // and onTransferFinished is where the client learns it is over. Off the main
            // thread and outside the post: this is cleanup, not UI.
            new Thread(wifiDirectHost::remove, "tarish-group-release").start();
            main.post(() -> {
                activeTransfer = 0;
                offerId = 0;   // whatever happened, the question is answered
                dismissPin();
                pinTransfer = 0;
                if (pinCancelled) {
                    // Our own doing, not the peer's. The daemon reports this as declined
                    // because from its side a refused PIN and a refused transfer end the
                    // same way, and blaming the other device would be a lie.
                    pinCancelled = false;
                    addRecord(TransferRecord.Outcome.CANCELLED, false,
                            xferPeerName, xferProtocol, xferNames, 0);
                    endTransferIdle("Cancelled", "you stopped it before anything was sent");
                } else if (status == STATUS_CANCELLED) {
                    // Incoming: a cancel can arrive at the prompt before we ever accepted,
                    // so the offer context is the reliable source, not the xfer fields.
                    addRecord(TransferRecord.Outcome.CANCELLED, true,
                            offerFrom, offerProtocol, offerNames, offerBytes);
                    endTransferIdle("Cancelled", "the sender stopped it");
                } else if (status == STATUS_DECLINED) {
                    // A Decline is an answer, not a fault; "could not send" would invite a
                    // retry that gets refused again.
                    addRecord(TransferRecord.Outcome.DECLINED, false,
                            xferPeerName, xferProtocol, xferNames, 0);
                    endTransferIdle("Declined", "the other device turned it down");
                } else if (status == STATUS_FAILED) {
                    addRecord(TransferRecord.Outcome.FAILED, !xferSending,
                            xferPeerName, xferProtocol, xferNames, xferSending ? 0 : offerBytes);
                    endTransferIdle("Could not send", "the transfer did not complete");
                } else if (!sendMode) {
                    collect();
                    completeTransfer("Received");
                } else {
                    addRecord(TransferRecord.Outcome.SENT, false,
                            xferPeerName, xferProtocol, xferNames, 0);
                    completeTransfer("Sent");
                }
            });
        }
    };

    // ------------------------------------------------------------------ ui ---

    /** The appearance this instance was built under, so onResume can tell it changed. */
    private int themeMode;

    // Force the chosen Light/Dark (or leave the system's) before any resource resolves.
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Theme.wrap(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        themeMode = Theme.mode(this);
        startDebugBridge();
        radios = new Radios(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Ui.bg(this));

        collectShared(getIntent());
        // Arriving from a share sheet means the user already chose to send.
        sendMode = !shared.isEmpty();

        setContentView(buildShell());
        connect();
        render();
    }

    /**
     * A share arriving while the screen is already open.
     *
     * The activity is singleTask, so a second share does NOT run onCreate -- without
     * this the files are dropped on the floor and the screen sits in whatever mode it
     * was already in, which is exactly what it did: sharing to an open Tarish showed the
     * receive screen and no files.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        shared.clear();
        collectShared(intent);
        if (!shared.isEmpty()) {
            sendMode = true;
            setDiscoverable(false, "onNewIntent");
            render();
        }
    }

    /** Title, scrolling content, and the mode bar pinned to the bottom. */
    private ViewGroup buildShell() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.bg(this));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 20);
        column.setPadding(side, Ui.dp(this, 24), side, Ui.dp(this, 24));
        column.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(side, bars.top + Ui.dp(this, 16), side, Ui.dp(this, 24));
            return insets;
        });

        // THE LOCKUP: the mark, then the lowercase name, tightly set. A plain title in the
        // system font is what every settings page looks like.
        //
        // This drew Glyph.Kind.BOLT -- a lightning bolt, which is the BARQ mark. Barq means
        // lightning; tarish is the one you send with a message. The app was renamed and the
        // artwork was not, so the header carried the old brand next to the new word.
        //
        // An ImageView with @drawable/ic_mark, not a Glyph: the mark is artwork with a fold
        // and three gradient strokes, and it is themed -- the near-white card cannot sit on a
        // light ground, so res/drawable-night holds the full-colour version and res/drawable
        // the dark-card one. The resource system picks; nothing here has to know which.
        LinearLayout mark = new LinearLayout(this);
        mark.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_mark);
        // The mark is taller than it is wide (75.2 x 86.6), so give it that ratio rather than
        // a square box, which would letterbox it and shrink the waves.
        int b = Ui.dp(this, 30);
        mark.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 26), b));
        TextView title = Ui.text(this, "tarish", 27, Ui.textColor(this), true);
        title.setLetterSpacing(-0.03f);
        title.setPadding(Ui.dp(this, 6), 0, 0, 0);
        mark.addView(title);

        // Push the gear to the far right of the wordmark row.
        View spacer = new View(this);
        mark.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        Glyph gear = new Glyph(this, Glyph.Kind.GEAR, Ui.textMuted(this));
        int g = Ui.dp(this, 26);
        gear.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SettingsActivity.class)));
        mark.addView(gear, new LinearLayout.LayoutParams(g, g));
        column.addView(mark);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content);

        scroller.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The docked "recent" panel: pinned between the scrolling stage and the mode bar, so
        // the history of what came in / went out is always in reach without scrolling past
        // the transfer stage. Capped in height, and rebuilt each render by buildDock().
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        int dside = Ui.dp(this, 20);
        dock.setPadding(dside, Ui.dp(this, 4), dside, Ui.dp(this, 6));
        page.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        nav = new BottomNav(this, this);
        nav.setBackgroundColor(Ui.surface(this));
        page.addView(nav);
        return page;
    }

    /** Rebuild the body for the current mode. */
    private void render() {
        content.removeAllViews();
        if (dock != null) {
            dock.removeAllViews();
        }
        nav.setMode(sendMode);
        // Every path that changes the mode or the policy comes through here, so this is
        // the one place the screen-awake decision has to be made.
        keepScreenAwake();
        // NO DAEMON, NO APP. Say so, rather than showing an empty device list.
        //
        // Without this the screen is truthful and useless: no peers, not discoverable,
        // no error -- indistinguishable from nobody being nearby. The app is the visible
        // half of something that mostly is not an app, and when the other half is absent
        // that is the only thing worth saying.
        // NO CREDENTIAL, NO APP. Checked before the daemon, because it is the more
        // actionable of the two and does not depend on the daemon being up.
        if (!AuthWindow.canAuthenticate(this)) {
            content.addView(buildNoCredentialCard());
            return;
        }
        // TARISH IS LOCKED IN ITS OWN RIGHT.
        //
        // An unlocked phone is not an unlocked Tarish. The device credential says who owns
        // the handset; it does not say the person holding it right now was meant to see
        // what arrived, or to send anything from here. This app is a door onto other
        // people's devices and onto files that landed on this one, so it locks separately
        // and relocks when its window ends -- the same bargain a password manager makes.
        //
        // This is the FIRST thing render() decides after the credential check, so nothing
        // below it can leak a peer name, a device name or an inbox entry to a glance.
        if (!AuthWindow.isOpen()) {
            content.addView(buildLockedCard());
            return;
        }
        if (service == null) {
            content.addView(buildNoDaemonCard());
            return;
        }
        View strip = protocolStrip();
        if (strip != null) {
            content.addView(strip);
        }
        View blocked = blockedNotice();
        if (blocked != null) {
            content.addView(blocked);
        }
        if (sendMode) {
            buildSend();
        } else {
            buildReceive();
        }
        buildDock();
    }

    /**
     * The docked recent panel, pinned at the bottom for the current direction.
     *
     * Received on the receive screen, sent on the send screen -- renderInbox filters by
     * direction. Height-capped so a long history scrolls inside the dock instead of pushing
     * the transfer stage off the top.
     */
    private void buildDock() {
        if (dock == null || service == null) {
            return;
        }
        inboxLabel = Ui.sectionLabel(this, sendMode ? "RECENT SENT" : "RECEIVED");
        dock.addView(inboxLabel);
        MaxHeightScrollView scroll = new MaxHeightScrollView(this, Ui.dp(this, 200));
        inbox = Ui.cardBox(this);
        inbox.setPadding(0, 0, 0, 0);
        scroll.addView(inbox);
        dock.addView(scroll);
        renderInbox();
    }

    /** Shown when the daemon is not on this device. */
    /**
     * No device credential, no Tarish.
     *
     * WHY THE WHOLE APP AND NOT JUST THE LOCKDOWN FEATURE. The authenticated window is the
     * thing that makes the VPN-lockdown exemption acceptable: the kill-switch holds unless a
     * person proves they are present. On a device with no PIN, pattern, password or
     * biometric there is no such proof available, so that guarantee cannot be offered at
     * all — and a device with no screen lock has no custody story for received files either.
     * Shipping sharing with the promise quietly absent is worse than not shipping it.
     *
     * It is also the honest reading of "authentication required": a control that silently
     * degrades to nothing when the user has not set a credential is not a control.
     *
     * Re-checked on every render, so enrolling a credential and coming back lifts this with
     * no restart.
     */
    /**
     * The lock screen. Everything else in the app is behind it.
     *
     * Deliberately says almost nothing: no device name, no peer list, no "3 files waiting".
     * A lock that advertises what it is protecting has given away the part that mattered to
     * someone glancing at the screen.
     */
    private LinearLayout buildLockedCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 28);
        box.setPadding(p, Ui.dp(this, 72), p, p);

        // No glyph: Glyph.Kind has no lock, and a wrong mark is worse than none on the one
        // screen whose whole job is to say plainly that this is shut.
        TextView title = Ui.text(this, "Tarish is locked", 24, Ui.textColor(this), true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView why = Ui.text(this,
                "Unlocking your phone does not unlock Tarish. Authenticate to share, and to "
                        + "see what has arrived.",
                14, Ui.textMuted(this), false);
        why.setGravity(Gravity.CENTER);
        why.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 26));
        box.addView(why);

        TextView action = Ui.text(this, "Unlock", 16, Ui.onAccent(this), true);
        action.setGravity(Gravity.CENTER);
        int ap = Ui.dp(this, 14);
        action.setPadding(Ui.dp(this, 44), ap, Ui.dp(this, 44), ap);
        action.setBackground(Ui.card(this, Ui.accent(this), Ui.accent(this), 14));
        action.setOnClickListener(v -> unlock("tapped"));
        box.addView(action);

        TextView note = Ui.text(this,
                "Stays unlocked for " + (AuthWindow.WINDOW_SECONDS / 60)
                        + " minutes, and relocks when you leave.",
                12, Ui.textFaint(this), false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 20), 0, 0);
        box.addView(note);
        return box;
    }

    /**
     * Authenticate and open the window.
     *
     * One prompt unlocks the app AND permits the lockdown exemption, because they are the
     * same claim: a person is here and said yes. Splitting them would mean two prompts for
     * one decision, and the second would be asking about something most people have no way
     * to evaluate.
     *
     * Not auto-fired from onResume. A prompt that appears by itself trains people to
     * approve prompts, and it also fires when the activity is merely being recreated --
     * a rotation should not ask anyone for a fingerprint.
     */
    private void unlock(String why) {
        if (authPromptUp) {
            return;   // one at a time; a second Builder would stack a second dialog
        }
        authPromptUp = true;
        Log.i(TAG, "unlock requested (" + why + ")");
        AuthWindow.request(this, service, ok -> {
            authPromptUp = false;
            if (ok) {
                Log.i(TAG, "unlocked for " + AuthWindow.WINDOW_SECONDS + "s");
            }
            render();
        });
    }

    private LinearLayout buildNoCredentialCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 28);
        box.setPadding(p, Ui.dp(this, 56), p, p);

        TextView title = Ui.text(this, "Set a screen lock to use Tarish", 22, Ui.textColor(this), true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView why = Ui.text(this,
                "Tarish can let sharing work while a VPN kill-switch is active, but only for "
                        + "a few minutes at a time and only when someone authenticates first. "
                        + "That is what keeps the exemption honest.\n\n"
                        + "This device has no PIN, pattern, password or biometric enrolled, so "
                        + "there is no way to authenticate \u2014 and no way to make that "
                        + "promise. Received files would have no lock in front of them either.",
                14, Ui.textMuted(this), false);
        why.setGravity(Gravity.CENTER);
        why.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 24));
        box.addView(why);

        TextView action = Ui.text(this, "Set a screen lock", 16, Ui.onAccent(this), true);
        action.setGravity(Gravity.CENTER);
        int ap = Ui.dp(this, 14);
        action.setPadding(Ui.dp(this, 28), ap, Ui.dp(this, 28), ap);
        action.setBackground(Ui.card(this, Ui.accent(this), Ui.accent(this), 14));
        action.setOnClickListener(v -> {
            // ACTION_SET_NEW_PASSWORD goes straight to enrolling one, rather than dropping
            // the person in the security menu to find it. Literal rather than the
            // DevicePolicyManager constant so this does not depend on that import.
            Intent i = new Intent("android.app.action.SET_NEW_PASSWORD");
            try {
                startActivity(i);
            } catch (Exception e) {
                // Fall back to the security screen if the direct action is unavailable.
                Log.w(TAG, "SET_NEW_PASSWORD unavailable, opening security settings", e);
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
                } catch (Exception e2) {
                    Log.w(TAG, "could not open security settings either", e2);
                }
            }
        });
        box.addView(action);

        TextView after = Ui.text(this,
                "Come back here once it is set \u2014 nothing else is needed.",
                12, Ui.textFaint(this), false);
        after.setGravity(Gravity.CENTER);
        after.setPadding(0, Ui.dp(this, 18), 0, 0);
        box.addView(after);
        return box;
    }

    private LinearLayout buildNoDaemonCard() {
        LinearLayout card = Ui.cardBox(this);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);

        card.addView(Ui.text(this, "Tarish is not installed on this device", 17, Ui.textColor(this), true));

        TextView why = Ui.text(this,
                "This app is the visible half of Tarish. The other half is a system service "
                        + "that holds the radio and speaks the protocols, and it has to be "
                        + "part of the operating system \u2014 it needs privileges no app can "
                        + "grant itself.\n\n"
                        + "Installing this app on its own cannot work, and nothing here will "
                        + "find a device.",
                13, Ui.textMuted(this), false);
        why.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        card.addView(why);

        card.addView(Ui.text(this,
                "github.com/tarish-dev/tarish-daemon",
                12, Ui.accent(this), false));

        TextView how = Ui.text(this,
                "That repository has the integration guide, including the one platform "
                        + "patch it needs.",
                11, Ui.textFaint(this), false);
        how.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(how);
        return card;
    }

    /**
     * Say when the direction being looked at is switched off, and by whom.
     *
     * Without this the screen is truthful and useless: no peers, nothing happening, no
     * reason given. Naming the SOURCE matters as much as the fact -- "off in Settings" is
     * something the person can act on, "set by your organization" is something they
     * should stop trying to act on.
     */
    /**
     * Is `protocol` usable right now, in the direction this screen is showing?
     *
     * PER PROTOCOL. AirDrop and Quick Share carry independent modes, and one off with the
     * other on is an ordinary configuration -- on a BCM4383 device AirDrop is switched
     * off precisely so AWDL stops taking Wi-Fi down, and Quick Share then does the
     * sharing. This read used to be policy.airdrop applied to everything, which cleared
     * the Quick Share peer list along with it: the protocol that was still enabled looked
     * broken, with the reason attributed to the wrong one.
     *
     * Direction matters too. Sending and receiving are separate grants, so a device may
     * legitimately be able to receive over Quick Share and not send over it.
     */
    private boolean allowed(int protocol) {
        if (policy == null) {
            return false;
        }
        int mode = protocol == ITarishService.PROTOCOL_AIRDROP
                ? policy.airdrop
                : policy.quickshare;
        return sendMode ? PolicyStore.allowsSend(mode) : PolicyStore.allowsReceive(mode);
    }

    /**
     * Hold the screen awake while this device is waiting to receive.
     *
     * WITHOUT THIS, "leave it on receive" silently stops working. The screen times out,
     * the activity pauses, onPause drops AirDrop visibility and stops the Quick Share BLE
     * advertiser, and the phone becomes undiscoverable -- while the policy still reads
     * airdrop=3 and mosey0 lingers, so nothing about the device looks wrong from the
     * inside. Reported from the field as AirDrop timing out and needing a restart, and
     * reproduced by letting the screen blank: the peer vanished from the other device's
     * list within seconds.
     *
     * Every automated test missed it because the harness runs `svc power stayon usb`, so
     * the screen never slept in any of them.
     *
     * Only while RECEIVING, and only while some protocol actually permits it. Sending is
     * a person standing at the phone, and a screen held awake for a mode that can receive
     * nothing is battery spent for no reason.
     */
    private void keepScreenAwake() {
        boolean waiting = !sendMode
                && (allowed(ITarishService.PROTOCOL_AIRDROP)
                    || allowed(ITarishService.PROTOCOL_QUICKSHARE));
        if (waiting) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * Open the shell control channel, on a debuggable build only.
     *
     * REFLECTIVELY, and that is the point. Bluetooth and Wi-Fi Direct sends are framework
     * API the daemon cannot reach, so an end-to-end harness has no way to drive them and
     * reported both as SKIP in every run — two transports never exercised against real
     * hardware. DebugBridge is the way in.
     *
     * It is excluded from a user build by Android.bp, so the class is simply not there.
     * Naming it directly would stop this file compiling; going through Class.forName means
     * the absence is ordinary rather than fatal. DebugBridge checks Build.IS_DEBUGGABLE
     * again on its own, and SELinux gates the socket a third time.
     */
    private void startDebugBridge() {
        if (!android.os.Build.IS_DEBUGGABLE) {
            return;
        }
        try {
            Class.forName("dev.tarish.app.DebugBridge")
                    .getDeclaredMethod("start")
                    .invoke(null);
        } catch (ClassNotFoundException absent) {
            // A debuggable build that was compiled without it. Nothing to say.
        } catch (Throwable t) {
            Log.w(TAG, "debug bridge did not start", t);
        }
    }

    /** Whether an administrator pinned this protocol, so the person cannot change it. */
    private boolean managedProtocol(int protocol) {
        if (policy == null) {
            return false;
        }
        return protocol == ITarishService.PROTOCOL_AIRDROP
                ? policy.airdropManaged
                : policy.quickshareManaged;
    }

    /**
     * What works right now, on the screen the person is already looking at.
     *
     * Two protocols with independent switches means "off" is never one fact about the
     * app, and Settings is the wrong place to learn it: the question this answers -- why
     * is there nothing here -- is asked on THIS screen, about a list that is empty or
     * half as long as expected. So each protocol states its own case here, for the
     * direction being shown.
     *
     * Always present, not only when something is wrong. A person who can see that Quick
     * Share is on and AirDrop is off does not have to wonder whether an empty list means
     * a policy or an empty room.
     */
    private View protocolStrip() {
        if (policy == null) {
            return null;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The chips sat directly under the wordmark and read as part of the title block
        // rather than as status about the device. The lockup needs air beneath it: the
        // mark and the word are one object, and anything crowding them joins that object.
        row.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 16));
        row.addView(protocolChip(ITarishService.PROTOCOL_AIRDROP, "AirDrop"));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 7), 1));
        row.addView(gap);
        row.addView(protocolChip(ITarishService.PROTOCOL_QUICKSHARE, "Quick Share"));
        return row;
    }

    private View protocolChip(int protocol, String label) {
        boolean on = allowed(protocol);
        boolean managed = managedProtocol(protocol);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(this, 10);
        int v = Ui.dp(this, 6);
        chip.setPadding(h, v, h, v);
        chip.setBackground(Ui.card(this, Ui.surfaceSunk(this), Ui.ruleColor(this), 999));

        chip.addView(Ui.dot(this, on ? Ui.live(this) : Ui.textFaint(this), 6));
        TextView t = Ui.text(this, label, 12, on ? Ui.textColor(this) : Ui.textFaint(this), false);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        chip.addView(t);

        // Only annotate the off case, and only with something the person can act on.
        // "Managed" tells them to stop trying; the absence of it means Settings will work.
        if (!on) {
            TextView why = Ui.text(this, managed ? "managed" : "off", 11, Ui.textFaint(this), false);
            LinearLayout.LayoutParams wp =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            wp.leftMargin = Ui.dp(this, 5);
            why.setLayoutParams(wp);
            chip.addView(why);
        }
        // TOGGLE IN PLACE, rather than sending the person to Settings to flip one switch
        // they are already looking at. The chip states the protocol and its state, so it
        // is the natural control for it.
        //
        // MANAGED STAYS LOCKED. An administrator's choice is not a default to be nudged:
        // when the field is pinned there is deliberately no listener at all, so the chip
        // does not depress, does not animate, and does nothing -- and the "managed" label
        // beside it already says why. Do not add a toast or a dialog here; a control that
        // reacts and then refuses is worse than one that plainly cannot be used.
        if (!managed) {
            chip.setOnClickListener(x -> toggleProtocol(protocol));
            chip.setOnLongClickListener(x -> {
                startActivity(new android.content.Intent(this, SettingsActivity.class));
                return true;
            });
        }
        return chip;
    }

    /**
     * Shown only when NEITHER protocol can do what this screen is for.
     *
     * The per-protocol case is the strip's job. This card is for the state where the
     * screen has nothing to offer at all, which is worth stating plainly rather than
     * leaving as an empty list under two off chips.
     */
    private View blockedNotice() {
        if (policy == null) {
            return null;
        }
        if (allowed(ITarishService.PROTOCOL_AIRDROP)
                || allowed(ITarishService.PROTOCOL_QUICKSHARE)) {
            return null;
        }
        String what = sendMode ? "Sending" : "Receiving";
        boolean bothManaged = managedProtocol(ITarishService.PROTOCOL_AIRDROP)
                && managedProtocol(ITarishService.PROTOCOL_QUICKSHARE);
        String why = bothManaged
                ? what + " is turned off by your organization."
                : what + " is turned off for both protocols. Turn one on in Settings.";

        LinearLayout card = Ui.cardBox(this);
        TextView t = Ui.text(this, why, 13, Ui.accent(this), false);
        int q = Ui.dp(this, 12);
        t.setPadding(q, q, q, q);
        card.addView(t);
        if (!bothManaged) {
            card.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, SettingsActivity.class)));
        }
        return card;
    }

    private void buildReceive() {
        // The big top area is one stage that morphs. A live receive fills it with progress; a
        // completion flashes there; an incoming offer takes it over; otherwise it is the
        // pulsing "ready to receive" beacon (or, when we cannot be seen, why not).
        if (stage == Stage.TRANSFER && !xferSending) {
            content.addView(buildProgressStage());
            return;
        }
        if (stage == Stage.DONE && stageDoneIncoming) {
            content.addView(buildDoneStage());
            return;
        }
        if (stage == Stage.OFFER && offerId != 0) {
            content.addView(Ui.sectionLabel(this, "Incoming"));
            content.addView(buildOfferCard());
            return;
        }
        // ONE hero for both states, ready and not.
        //
        // This used to be a big pulsing beacon when discoverable and a small strip when not
        // -- which is exactly backwards: the state that REQUIRES AN ACTION was the one drawn
        // small, with the tap target a 13sp grey line inside it. Reported as "I cannot change
        // from not visible, clicking not doing anything". The device-name bubble is gone with
        // it; the name now lives in the ready subtitle, where it answers a question a person
        // actually has ("what will they see?") instead of restating the phone they are holding.
        content.addView(buildReceiveHero());
    }

    /**
     * "X wants to send you Y" — with the two answers, and no default.
     *
     * Deliberately not a system dialog: this is the one screen in Tarish where a person
     * is being asked to trust another device, and it should look like the rest of the
     * app rather than like something the platform threw up. It is also the only view
     * that can be sure it is on top, because receiving requires the app to be open.
     */
    private LinearLayout buildOfferCard() {
        LinearLayout card = Ui.cardBox(this);

        // A file-type mark on the left, the request on the right. The mark tells the person
        // at a glance whether this is a photo, a video or a document before they read a word
        // -- the same first cue AirDrop gives.
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        int mark = Ui.dp(this, 44);
        topRow.addView(Ui.glyphInCircle(this, Glyph.kindForNames(offerNames),
                Ui.accent(this), Ui.surfaceSunk(this), 44),
                new LinearLayout.LayoutParams(mark, mark));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = Ui.dp(this, 12);
        col.setLayoutParams(colLp);

        // Name the protocol on the prompt. "Someone wants to send you a file" is a
        // different decision over AirDrop than over Quick Share -- different world,
        // different set of people who could be nearby -- and the sender's name does not
        // say which. It sits ABOVE the name so it is read before the decision, not
        // after it.
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setPadding(0, 0, 0, Ui.dp(this, 8));
        badgeRow.addView(Ui.badge(this,
                offerProtocol == ITarishService.PROTOCOL_QUICKSHARE ? "Quick Share" : "AirDrop"));
        col.addView(badgeRow);

        col.addView(Ui.text(this, offerFrom + " wants to send", 17, Ui.textColor(this), true));

        String what;
        if (offerNames.length == 0) {
            what = "a file";
        } else if (offerNames.length == 1) {
            what = offerNames[0];
        } else {
            what = offerNames.length + " files — " + String.join(", ", offerNames);
        }
        TextView detail = Ui.text(this, what, 13, Ui.textFaint(this), false);
        detail.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        col.addView(detail);

        topRow.addView(col);
        card.addView(topRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView decline = Ui.text(this, "DECLINE", 13, Ui.textColor(this), true);
        decline.setGravity(Gravity.CENTER);
        decline.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        decline.setBackground(Ui.card(this, Ui.surface(this), Ui.ruleColor(this), 10));
        decline.setOnClickListener(v -> answerOffer(false));
        row.addView(decline, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));

        TextView accept = Ui.text(this, "ACCEPT", 13, Ui.bg(this), true);
        accept.setGravity(Gravity.CENTER);
        accept.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        accept.setBackground(Ui.card(this, Ui.accent(this), Ui.accent(this), 10));
        accept.setOnClickListener(v -> answerOffer(true));
        row.addView(accept, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);
        return card;
    }

    private void answerOffer(boolean accept) {
        long id = offerId;
        offerId = 0;
        if (service == null) {
            render();
            return;
        }
        try {
            service.respondToOffer(id, accept);
        } catch (Exception e) {
            Log.w(TAG, "could not answer offer " + id, e);
        }
        if (accept) {
            activeTransfer = id;
            TransferService.watch(getApplicationContext(), id, describeOffer(), false);
            render();
            String what = describeOffer();
            if (offerBytes > 0) {
                what = what.isEmpty() ? Ui.size(offerBytes) : what + "  ·  " + Ui.size(offerBytes);
            }
            beginTransfer(offerFrom, what, offerProtocol, false, offerNames);
            // PIN VERIFICATION INTENTIONALLY DISABLED — see docs/PIN-DISABLED.md. No code is
            // shown on accept any more (pendingReceiverPin is never set):
            //   if (pendingReceiverPin != null) { showReceiverPin(pendingReceiverPin); }
        } else {
            // Declined: leave the OFFER stage so the screen returns to the beacon.
            pendingReceiverPin = null;
            stage = Stage.IDLE;
            render();
        }
    }

    /**
     * Name, and a live dot when discoverable.
     *
     * A strip rather than a card with a big glyph: this is a status line, and the
     * previous version gave the device's own name more weight than anything the user
     * came here to do.
     */
    /**
     * The receive screen's one control: ready or not, and tappable when it is not.
     *
     * <p>Replaces a split where discoverable drew a large pulsing beacon and not-discoverable
     * drew a small strip — the wrong way round, because the state needing an action was the
     * one drawn small, with the tap target a grey subtitle inside it. Now both states are the
     * same hero, the whole card is the target, and the device name lives in the ready
     * subtitle rather than in a bubble of its own.
     *
     * <p>The fields {@code identity}, {@code liveDot} and {@code stateLine} are kept because
     * {@code setIdentityState()} is called from a dozen places (service death, reconnects,
     * transfer outcomes) and all of them should keep working without knowing this changed.
     */
    private View buildReceiveHero() {
        // NO CARD. This is the screen's centrepiece, not a row in a list, and a box around it
        // makes it read as one more widget rather than as the thing you came here for. An
        // earlier version used Ui.cardBox() to advertise "this is tappable" -- the right way
        // to do that is a big target and a line saying what tapping does, which the states
        // below provide. The previous beacon had no card either, and looked better for it.
        identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 34);
        identity.setPadding(0, pad, 0, pad);

        // The ring pulses only while genuinely discoverable. A hero that animates when
        // nothing is listening is the same class of lie as the label that used to say "not
        // visible" while Quick Share was advertising.
        int ringBox = Ui.dp(this, 140);
        android.widget.FrameLayout ring = new android.widget.FrameLayout(this);
        liveDot = new PulseView(this);
        ring.addView(liveDot, new android.widget.FrameLayout.LayoutParams(ringBox, ringBox));
        View puck = Ui.glyphInCircle(this, Glyph.Kind.DOWNLOAD,
                discoverable ? Ui.live(this) : Ui.textFaint(this), Ui.surfaceSunk(this), 64);
        int puckBox = Ui.dp(this, 64);
        android.widget.FrameLayout.LayoutParams pp =
                new android.widget.FrameLayout.LayoutParams(puckBox, puckBox);
        pp.gravity = Gravity.CENTER;
        ring.addView(puck, pp);
        identity.addView(ring, new LinearLayout.LayoutParams(ringBox, ringBox));

        deviceLine = Ui.text(this, "", 19, Ui.textColor(this), true);
        deviceLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(this, 14);
        deviceLine.setLayoutParams(tl);
        identity.addView(deviceLine);

        stateLine = Ui.text(this, "", 14, Ui.textMuted(this), false);
        stateLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 4);
        stateLine.setLayoutParams(sl);
        identity.addView(stateLine);

        // The countdown writes into the subtitle, so "Pixel 10 Pro · 9:54 left"
        // updates in place without rebuilding the hero and restarting the pulse.
        visibleCountdownView = stateLine;
        // Re-state what is actually true, rather than a default that may already be wrong.
        // POLICY BEATS THE RADIO in the explanation, because only one of them is the
        // person's own doing. With AirDrop switched off the chip said "off" while this
        // line still said "AirDrop radio unavailable" -- two explanations for one
        // protocol, side by side, disagreeing about whose decision it was. If they turned
        // it off, say that; the radio is not the reason and mentioning it invites them to
        // go looking for a fault that is not there.
        if (!transportUp() && allowed(ITarishService.PROTOCOL_AIRDROP)) {
            heroReady = false;
            setHeroTitle("Cannot receive");
            setIdentityState("AirDrop radio unavailable \u2014 see Send screen", false);
        } else {
            // SAY WHY, not just what. Bare "not visible" appears while sending -- which is
            // correct, visibility is deliberately off in send mode -- but read against an
            // incoming prompt on the same screen it looks like a contradiction. Naming the
            // reason turns a puzzle into a statement.
            // SAY THE SCREEN IS BEING HELD AWAKE. Waiting to receive keeps the display on
            // (see keepScreenAwake), and a phone whose screen will not sleep with no
            // explanation reads as a fault. One clause turns it into a statement.
            boolean canReceive = policy != null && PolicyStore.allowsReceive(policy.airdrop);
            boolean qsReceive = policy != null && PolicyStore.allowsReceive(policy.quickshare);
            // Clear any handler a previous render attached. Only the branch that can
            // actually act sets one, and a leftover listener is how a control ends up
            // looking live while doing nothing.
            identity.setOnClickListener(null);
            identity.setClickable(false);
            if (!discoverable && !sendMode && canReceive) {
                // The ten-minute window has lapsed (or was never opened). Offer a deliberate
                // re-enable rather than silently renewing: tap to be ready for another 10.
                // The WHOLE CARD is the target now, not a line of grey text inside it.
                heroReady = false;
                setHeroTitle("Not receiving");
                setIdentityState("Tap to unlock receiving for 10 minutes", false);
                identity.setClickable(true);
                identity.setOnClickListener(v -> {
                    // No second prompt. Unlocking the app already authenticated this
                    // person for this window; becoming discoverable is a choice within it,
                    // not a new claim about who is holding the phone.
                    setDiscoverable(true, "re-enable");
                    render();
                });
            } else if (!discoverable && !sendMode && qsReceive) {
                // THIS LINE USED TO LIE.
                //
                // Everything above it is about AirDrop: `discoverable` governs the mDNS
                // advertisement and the httpd that answers /Discover, and canReceive reads
                // policy.airdrop alone. Quick Share USED TO advertise on its own schedule,
                // over BLE from this app and over LAN mDNS from the daemon, consulting none
                // of this -- so with AirDrop off and Quick Share on, the screen said "not
                // visible" while the device was discoverable and receiving. Confirmed from
                // the log at the moment the screen read that:
                //
                //   TarishQsReceiver: advertising as a Quick Share endpoint (57 bytes)
                //   tarishsharingd: quickshare: advertisement for hybs over BLE
                //
                // The operator reported it as "I cannot change from not visible, clicking
                // not doing anything" -- while a transfer was in fact arriving.
                //
                // Both Quick Share advertisers now follow the same window (#40), so this
                // branch is about POLICY -- AirDrop off, Quick Share on -- and no longer
                // about two components disagreeing on whether we are visible.
                heroReady = false;
                setHeroTitle("Ready for Quick Share");
                setIdentityState("AirDrop is off — turn it on in Settings", false);
            } else if (discoverable) {
                heroReady = true;
                setHeroTitle("Ready to receive");
                // The device name belongs HERE: "what will they see?" is a question a person
                // actually has, and this is the moment they have it. The countdown overwrites
                // this line on the next tick -- see updateVisibleCountdown.
                setIdentityState(deviceName(), true);
            } else {
                heroReady = false;
                setHeroTitle("Not receiving");
                setIdentityState(sendMode ? "Not visible while sending" : "Not visible", false);
            }
        }
        return identity;
    }

    /**
     * Recent activity, newest first: what arrived, what was sent, and what did not go
     * through -- cancelled, declined, failed.
     *
     * The old list held only files that landed, so it could not answer "did that send?"
     * or "why did nothing come?". Received files stay openable in place; every row opens a
     * details sheet with who, when, how big and over what.
     */
    private void renderInbox() {
        if (inbox == null) {
            return;
        }
        inbox.removeAllViews();
        // This screen shows only its OWN direction: the receive page lists what came in,
        // the send page what went out. Mixing "Sent" rows into the receive page read as a
        // bug -- a receive screen should not report sends.
        List<TransferRecord> shown = new ArrayList<>();
        for (TransferRecord r : activity) {
            if (r.incoming == !sendMode) {
                shown.add(r);
            }
        }
        String label = sendMode ? "RECENT SENT" : "RECEIVED";
        if (shown.isEmpty()) {
            TextView empty = Ui.text(this,
                    sendMode ? "Nothing sent yet" : "Nothing received yet",
                    13, Ui.textFaint(this), false);
            int p = Ui.dp(this, 16);
            empty.setPadding(p, p, p, p);
            inbox.addView(empty);
            inboxLabel.setText(label);
            return;
        }
        inboxLabel.setText(label + "  ·  " + shown.size());
        for (int i = shown.size() - 1; i >= 0; i--) {
            if (i < shown.size() - 1) {
                inbox.addView(Ui.rule(this));
            }
            inbox.addView(recordRow(shown.get(i)));
        }
    }

    private View recordRow(TransferRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, p, p);
        // The whole row opens details -- who sent it, when, how big, over what.
        row.setOnClickListener(v -> showRecordDetails(r));

        int box = Ui.dp(this, 40);
        if (r.isPreviewable()) {
            // A single photo/video that landed: show a real thumbnail. Until it loads (or
            // if it never does) the type mark sits in its place.
            android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
            holder.addView(Ui.glyphInCircle(this, r.icon(), Ui.textMuted(this),
                    Ui.surfaceSunk(this), 40));
            row.addView(holder, new LinearLayout.LayoutParams(box, box));
            if (r.thumb != null) {
                paintThumb(holder, r.thumb);
            } else {
                loadRecordThumb(r, holder);
            }
        } else {
            int ink = r.outcome == TransferRecord.Outcome.FAILED
                    ? Ui.error(this) : Ui.textMuted(this);
            row.addView(Ui.glyphInCircle(this, r.icon(), ink, Ui.surfaceSunk(this), 40),
                    new LinearLayout.LayoutParams(box, box));
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        col.setLayoutParams(lp);

        TextView n = Ui.text(this, r.title(), 14, Ui.textColor(this), false);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);

        // "Received · Alice · 2m · 4.1 MB", the outcome word coloured for what happened.
        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.addView(Ui.text(this, r.outcomeWord(), 12, outcomeColor(r), true));
        String rest = "  ·  " + r.peerLabel() + "  ·  " + ago(r.whenMs);
        if (r.bytes > 0) {
            rest = rest + "  ·  " + Ui.size(r.bytes);
        }
        sub.addView(Ui.text(this, rest, 12, Ui.textFaint(this), false));
        col.addView(sub);
        row.addView(col);

        FileCollector.Stored single = r.singleFile();
        if (single != null) {
            // QUIET: outlined, not filled -- signal amber is the send action, not every row.
            TextView open = Ui.text(this, "OPEN", 11, Ui.accent(this), true);
            open.setLetterSpacing(0.1f);
            open.setGravity(Gravity.CENTER);
            open.setBackground(Ui.card(this, Color.TRANSPARENT, Ui.accent(this), 8));
            open.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            open.setOnClickListener(v -> openFile(single));
            row.addView(open);
        }
        return row;
    }

    private int outcomeColor(TransferRecord r) {
        switch (r.outcome) {
            case RECEIVED:
            case SENT:
                return Ui.live(this);
            case FAILED:
                return Ui.error(this);
            default:
                return Ui.textMuted(this);   // cancelled, declined
        }
    }

    /** Record an outcome in the activity list and refresh it. */
    private void addRecord(TransferRecord.Outcome o, boolean incoming, String peer,
                           int protocol, String[] names, long bytes) {
        activity.add(new TransferRecord(o, incoming, peer, protocol, names, bytes,
                java.util.Collections.emptyList()));
        renderInbox();
    }

    private void paintThumb(android.widget.FrameLayout holder, android.graphics.Bitmap bmp) {
        holder.removeAllViews();
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bmp);
        iv.setBackground(Ui.card(this, Ui.surfaceSunk(this), Ui.ruleColor(this), 10));
        iv.setClipToOutline(true);
        holder.addView(iv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Load a received file's thumbnail once, cache it on the record, and paint it in. */
    private void loadRecordThumb(TransferRecord r, android.widget.FrameLayout holder) {
        if (r.thumbTried) {
            return;
        }
        r.thumbTried = true;
        FileCollector.Stored f = r.singleFile();
        if (f == null || f.uri == null) {
            return;
        }
        final Uri uri = f.uri;
        final boolean video = r.icon() == Glyph.Kind.VIDEO;
        new Thread(() -> {
            android.graphics.Bitmap ready = loadPreview(uri, video);
            if (ready == null) {
                return;
            }
            final android.graphics.Bitmap bmp = ready;
            main.post(() -> {
                r.thumb = bmp;
                paintThumb(holder, bmp);
            });
        }, "tarish-thumb").start();
    }

    /**
     * A preview bitmap for a photo or video URI, robust across sources.
     *
     * loadThumbnail is the fast path but silently gives nothing for many videos in the
     * Downloads collection and some SAF documents. So: try it, then fall back to pulling a
     * frame from the video with MediaMetadataRetriever (works from any readable fd) or
     * decoding an image stream downsampled. Logs which path won, so a missing preview is
     * diagnosable rather than a silent nothing.
     */
    private android.graphics.Bitmap loadPreview(Uri uri, boolean video) {
        try {
            android.graphics.Bitmap b = getContentResolver()
                    .loadThumbnail(uri, new android.util.Size(160, 160), null);
            if (b != null) {
                return b;
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadThumbnail failed for " + uri + " (" + t + "); falling back");
        }
        if (video) {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    return null;
                }
                r.setDataSource(pfd.getFileDescriptor());
                android.graphics.Bitmap frame = r.getFrameAtTime(-1);
                if (frame == null) {
                    Log.w(TAG, "no video frame for " + uri);
                }
                return frame;
            } catch (Throwable t) {
                Log.w(TAG, "video frame extract failed for " + uri, t);
                return null;
            } finally {
                try {
                    r.release();
                } catch (Throwable ignored) {
                    // release() throwing tells us nothing useful.
                }
            }
        }
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            return android.graphics.BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable t) {
            Log.w(TAG, "image decode fallback failed for " + uri, t);
            return null;
        }
    }

    /** The full "who, when, how big, over what" for one activity entry. */
    private void showRecordDetails(TransferRecord r) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        body.setPadding(pad, pad, pad, Ui.dp(this, 8));

        body.addView(Ui.text(this, r.title(), 19, Ui.textColor(this), true));
        TextView dir = Ui.text(this, r.directionLine(), 13, outcomeColor(r), true);
        dir.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        body.addView(dir);

        body.addView(detailLine("When", fullTime(r.whenMs)));
        body.addView(detailLine("Peer", r.peerLabel()));
        body.addView(detailLine("Over", r.protocol == ITarishService.PROTOCOL_QUICKSHARE
                ? "Quick Share" : "AirDrop"));
        if (r.bytes > 0) {
            body.addView(detailLine("Size", Ui.size(r.bytes)));
        }
        if (r.names.length > 1) {
            body.addView(detailLine("Files", String.valueOf(r.names.length)));
        }

        // Each landed file, openable in place.
        if (!r.files.isEmpty()) {
            View gap = new View(this);
            body.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 8)));
            for (FileCollector.Stored f : r.files) {
                LinearLayout fr = new LinearLayout(this);
                fr.setGravity(Gravity.CENTER_VERTICAL);
                fr.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
                TextView fn = Ui.text(this, f.name, 13, Ui.textColor(this), false);
                fn.setMaxLines(1);
                fn.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                fr.addView(fn, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView open = Ui.text(this, "OPEN", 11, Ui.accent(this), true);
                open.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), 0, Ui.dp(this, 4));
                open.setOnClickListener(v -> openFile(f));
                fr.addView(open);
                body.addView(fr);
            }
        }

        new android.app.AlertDialog.Builder(this)
                .setView(body)
                .setPositiveButton("Done", null)
                .show();
    }

    private LinearLayout detailLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.addView(Ui.text(this, label, 13, Ui.textFaint(this), false),
                new LinearLayout.LayoutParams(Ui.dp(this, 64),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(Ui.text(this, value, 13, Ui.textColor(this), false));
        return row;
    }

    private String fullTime(long when) {
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(when));
    }

    private void openFile(FileCollector.Stored f) {
        if (f.uri == null) {
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setData(f.uri);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Exception e) {
            Log.w(TAG, "nothing can open " + f.name, e);
        }
    }

    private static String ago(long when) {
        long secs = Math.max(0, (System.currentTimeMillis() - when) / 1000);
        if (secs < 60) {
            return "just now";
        }
        long mins = secs / 60;
        return mins < 60 ? mins + " min ago" : (mins / 60) + " h ago";
    }

    private void buildSend() {
        // The send area morphs like receive: a live send fills it with the progress stage,
        // a completion flashes there, otherwise the file/note controls and the peer list.
        if (stage == Stage.TRANSFER && xferSending) {
            content.addView(buildProgressStage());
            return;
        }
        if (stage == Stage.DONE && !stageDoneIncoming) {
            content.addView(buildDoneStage());
            return;
        }
        content.addView(Ui.sectionLabel(this, "Files"));
        LinearLayout files = Ui.cardBox(this);
        files.setOrientation(LinearLayout.HORIZONTAL);
        files.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);
        String heading = outcomeTitle != null ? outcomeTitle
                : shared.isEmpty() ? "No files chosen" : describeShared();
        String sub = outcomeDetail != null ? outcomeDetail
                : shared.isEmpty() ? "choose files, or share to tarish from any app"
                                   : firstNames();
        col.addView(Ui.text(this, heading, 15,
                            outcomeTitle != null ? Ui.accent(this) : Ui.textColor(this), true));
        col.addView(Ui.text(this, sub, 12, Ui.textFaint(this), false));
        files.addView(col);

        // Tarish should be usable on its own, not only as a share target. Without this
        // the send half of the app could do nothing unless another app started it.
        TextView choose = Ui.text(this, shared.isEmpty() ? "CHOOSE" : "CHANGE",
                                  11, Ui.onAccent(this), true);
        choose.setLetterSpacing(0.1f);
        choose.setGravity(Gravity.CENTER);
        choose.setBackground(Ui.card(this, Ui.accent(this), Color.TRANSPARENT, 8));
        choose.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        choose.setOnClickListener(v -> pickFiles());
        files.addView(choose);
        content.addView(files);

        // Send text without a file: copy from any app, paste here, send it as a note. The
        // receiver -- Android or Apple -- gets it as a small .txt.
        TextView note = Ui.text(this, "Or type a note to send", 12, Ui.accent(this), true);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 2));
        note.setOnClickListener(v -> composeNote());
        content.addView(note);

        // Choosing again clears a previous outcome, so the row stops reporting a
        // transfer the user has moved on from.
        // Section label with a refresh affordance on the right.
        //
        // WHY A MANUAL REFRESH EXISTS. Discovery is mDNS, so a peer that goes away
        // stays listed until its TTL runs out, and one that was never fully resolved
        // stays half-resolved. On a link that flaps -- frankel, where AWDL owns the
        // radio and Wi-Fi scanning contends with it -- that stale state is exactly
        // what the user is looking at when they wonder why their Mac is not there.
        // Waiting out a TTL is not something a person should have to know about.
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.sectionLabel(this,
                shared.isEmpty() ? "Nearby devices" : "Send to nearby devices");
        labelRow.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Ui.text(this, "REFRESH", 11, Ui.accent(this), true);
        refresh.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        refresh.setOnClickListener(v -> forceRediscover());
        labelRow.addView(refresh);
        content.addView(labelRow);
        peerBox = Ui.cardBox(this);
        peerBox.setMinimumHeight(Ui.dp(this, 150));
        peerSignature = null;   // fresh views, so the next refresh MUST populate them
        content.addView(peerBox);
        if (shared.isEmpty()) {
            TextView hint = Ui.text(this, "Choose files to enable sending",
                                    12, Ui.textFaint(this), false);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Ui.dp(this, 10), 0, 0);
            content.addView(hint);
        }

        // The recent list (sent / declined / failed) is the docked panel that buildDock()
        // renders for both directions -- it used to be built inline here too, which put a
        // second "RECENT SENT" on the send page.

        refreshPeers();
    }

    /**
     * The live transfer, filling the big stage: a progress ring around the file's icon, the
     * peer, a state word, the file(s), a byte line with speed and ETA, and Cancel. Reads the
     * xfer* fields (set by beginTransfer); updateProgress then updates the ring and byte line
     * in place. This is the AirDrop-style progress, using the whole area rather than a strip.
     */
    private View buildProgressStage() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));

        int ringBox = Ui.dp(this, 148);
        android.widget.FrameLayout ring = new android.widget.FrameLayout(this);
        progressRing = new RingView(this);
        progressRing.setColor(Ui.accent(this));
        progressRing.setIndeterminate(true);
        ring.addView(progressRing, new android.widget.FrameLayout.LayoutParams(ringBox, ringBox));
        progressIcon = new android.widget.FrameLayout(this);
        int iconBox = Ui.dp(this, 64);
        android.widget.FrameLayout.LayoutParams ip =
                new android.widget.FrameLayout.LayoutParams(iconBox, iconBox);
        ip.gravity = Gravity.CENTER;
        ring.addView(progressIcon, ip);
        setCardIcon(Glyph.kindForNames(xferNames), Ui.accent(this));   // fills progressIcon
        wrap.addView(ring, new LinearLayout.LayoutParams(ringBox, ringBox));

        progressState = Ui.text(this, xferSending ? "SENDING" : "RECEIVING", 11,
                Ui.accent(this), true);
        progressState.setAllCaps(true);
        progressState.setLetterSpacing(0.08f);
        progressState.setGravity(Gravity.CENTER);
        wrap.addView(progressState, centeredTop(16));

        progressPeer = Ui.text(this, xferPeerName == null || xferPeerName.isEmpty()
                ? "A nearby device" : xferPeerName, 17, Ui.textColor(this), true);
        progressPeer.setGravity(Gravity.CENTER);
        wrap.addView(progressPeer, centeredTop(2));

        progressWhat = Ui.text(this, describeXfer(), 13, Ui.textFaint(this), false);
        progressWhat.setGravity(Gravity.CENTER);
        wrap.addView(progressWhat, centeredTop(2));

        // Tabular: it counts up several times a second, and proportional digits make the
        // whole line jump sideways on every tick.
        progressLabel = Ui.tabular(Ui.text(this, "", 13, Ui.textMuted(this), false));
        progressLabel.setGravity(Gravity.CENTER);
        wrap.addView(progressLabel, centeredTop(8));

        TextView cancel = Ui.text(this, "Cancel", 13, Ui.onAccent(this), true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(Ui.dp(this, 26), Ui.dp(this, 10), Ui.dp(this, 26), Ui.dp(this, 10));
        cancel.setBackground(Ui.card(this, Ui.accentFill(this), Color.TRANSPARENT, 20));
        cancel.setOnClickListener(v -> cancelActive());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 18);
        cancel.setLayoutParams(cp);
        wrap.addView(cancel);

        // On send we hold the file, so a single image/video shows a real thumbnail in the ring.
        if (xferSending) {
            maybeLoadSendThumbnail();
        }
        return wrap;
    }

    /** The completion flash filling the stage: a full jade ring, a check, the outcome and peer. */
    private View buildDoneStage() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));

        int ringBox = Ui.dp(this, 148);
        android.widget.FrameLayout ring = new android.widget.FrameLayout(this);
        RingView r = new RingView(this);
        r.setColor(Ui.live(this));
        r.setFraction(1f);
        ring.addView(r, new android.widget.FrameLayout.LayoutParams(ringBox, ringBox));
        View check = Ui.glyphInCircle(this, Glyph.Kind.CHECK, Ui.live(this),
                Ui.surfaceSunk(this), 64);
        int iconBox = Ui.dp(this, 64);
        android.widget.FrameLayout.LayoutParams ip =
                new android.widget.FrameLayout.LayoutParams(iconBox, iconBox);
        ip.gravity = Gravity.CENTER;
        ring.addView(check, ip);
        wrap.addView(ring, new LinearLayout.LayoutParams(ringBox, ringBox));

        TextView word = Ui.text(this, stageDoneWord == null ? "Done" : stageDoneWord,
                18, Ui.live(this), true);
        word.setGravity(Gravity.CENTER);
        wrap.addView(word, centeredTop(16));

        TextView peer = Ui.text(this, xferPeerName == null || xferPeerName.isEmpty()
                ? "" : (stageDoneIncoming ? "from " : "to ") + xferPeerName,
                13, Ui.textFaint(this), false);
        peer.setGravity(Gravity.CENTER);
        wrap.addView(peer, centeredTop(2));
        return wrap;
    }

    /** A full-width, top-margined, centered layout param — the stage stacks these. */
    private LinearLayout.LayoutParams centeredTop(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    /** File name(s)/count for the current transfer. */
    private String describeXfer() {
        if (xferNames == null || xferNames.length == 0) {
            return "";
        }
        return xferNames.length == 1 ? xferNames[0] : xferNames.length + " files";
    }

    /**
     * Begin a transfer: stash its context, reset speed tracking, and switch the stage to the
     * live progress view. render() builds buildProgressStage from these fields; updateProgress
     * then updates the ring and byte line in place.
     *
     * @param peer     the device on the other end
     * @param what     unused now (kept for call sites); the stage derives its text from names
     * @param protocol AirDrop or Quick Share
     * @param sending  true when we are the sender
     * @param names    file/note names, for the icon, the record, and the "what" line
     */
    private void beginTransfer(String peer, String what, int protocol, boolean sending,
                              String[] names) {
        xferSending = sending;
        xferPeerName = peer;
        xferProtocol = protocol;
        xferNames = names == null ? new String[0] : names;
        xferStartMs = android.os.SystemClock.elapsedRealtime();
        xferLastMs = xferStartMs;
        xferLastBytes = 0;
        xferRate = 0;
        xferToken++;   // a fresh transfer cancels any pending "Done -> idle" hide
        stage = Stage.TRANSFER;
        render();
    }

    /** Return the stage to idle, recording a send-side outcome on the way if there is one. */
    private void endTransferIdle(String sendTitle, String sendDetail) {
        if (sendMode) {
            outcomeTitle = sendTitle;
            outcomeDetail = sendDetail;
        }
        stage = Stage.IDLE;
        render();
    }

    /** Put a type mark on the card, tinted for the current state. */
    private void setCardIcon(Glyph.Kind kind, int ink) {
        if (progressIcon == null) {
            return;
        }
        progressIcon.removeAllViews();
        progressIcon.addView(Ui.glyphInCircle(this, kind, ink, Ui.surfaceSunk(this), 40));
    }

    /** Swap the whole icon holder for an arbitrary view (a real thumbnail). */
    private void setCardIconView(View v) {
        if (progressIcon == null) {
            return;
        }
        progressIcon.removeAllViews();
        progressIcon.addView(v, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * On send, replace the type mark with a real thumbnail when a single image is going out.
     *
     * <p>Only for one image: a batch has no single representative, and only images have a
     * cheap thumbnail. {@link android.content.ContentResolver#loadThumbnail} already returns
     * a downscaled bitmap, so there is no full-size decode to blow up memory. Off the main
     * thread because it touches the provider; guarded by {@link #xferToken} so a slow load
     * that finishes after the transfer is gone does not paint onto the next one's card.
     */
    private void maybeLoadSendThumbnail() {
        if (shared.size() != 1) {
            return;
        }
        final Uri uri = shared.get(0);
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception ignored) {
            // A provider that will not answer its own type is not one we chase for a preview.
        }
        if (type == null || !(type.startsWith("image/") || type.startsWith("video/"))) {
            return;
        }
        final boolean video = type.startsWith("video/");
        final long token = xferToken;
        new Thread(() -> {
            android.graphics.Bitmap ready = loadPreview(uri, video);
            if (ready == null) {
                Log.w(TAG, "no send preview for " + uri);
                return;
            }
            main.post(() -> {
                // Nothing newer took the card, and it is still up.
                if (token != xferToken || stage != Stage.TRANSFER || progressIcon == null) {
                    return;
                }
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(ready);
                iv.setBackground(Ui.card(this, Ui.surfaceSunk(this), Color.TRANSPARENT, 10));
                iv.setClipToOutline(true);
                setCardIconView(iv);
            });
        }, "tarish-thumb").start();
    }

    /**
     * Flash the card as complete, then take it down.
     *
     * A jade check with the outcome word, held briefly so the person sees the transfer
     * landed rather than the card just vanishing. The hide is guarded by {@link #xferToken}
     * so a new transfer that starts inside the window keeps its own card.
     */
    private void completeTransfer(String word) {
        stageDoneWord = word;
        stageDoneIncoming = !xferSending;
        stage = Stage.DONE;
        hapticDone();
        render();
        // Hold the flash briefly, then fall back to idle (the beacon on receive, the send
        // controls on send). Guarded so a new transfer inside the window keeps its own stage.
        final long token = ++xferToken;
        main.postDelayed(() -> {
            if (token == xferToken && activeTransfer == 0) {
                stage = Stage.IDLE;
                stageDoneWord = null;
                render();
            }
        }, 1600);
    }

    /**
     * Fold live byte counts into the card: fraction, and a "3.4 MB of 21.6 MB · 8.2 MB/s ·
     * 3s left" line, plus a smoothed speed and estimate.
     *
     * Also adopts a transfer nobody announced (auto-accept, no offer) by opening the card
     * with a generic header rather than dropping the update on the floor.
     */
    private void updateProgress(long done, long total) {
        if (stage != Stage.TRANSFER) {
            // A transfer nobody announced (auto-accept, no offer): open the stage so the
            // update has somewhere to land rather than being dropped on the floor.
            beginTransfer(offerFrom != null ? offerFrom : "A nearby device",
                    describeOffer(), offerProtocol, false, offerNames);
        }
        if (progressRing == null) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        long dt = now - xferLastMs;
        if (dt >= 250) {
            double inst = (done - xferLastBytes) * 1000.0 / dt;   // bytes/sec
            xferRate = xferRate <= 0 ? inst : 0.7 * xferRate + 0.3 * inst;
            xferLastMs = now;
            xferLastBytes = done;
        }

        // Waiting (no total yet) keeps the ring spinning; once bytes flow, show the
        // percentage on the state line and fill the ring to match.
        int pct = total > 0 ? (int) (done * 100 / total) : -1;
        setProgressState(
                (xferSending ? "Sending" : "Receiving") + (pct >= 0 ? "  ·  " + pct + "%" : ""),
                Ui.accent(this));
        if (total > 0) {
            progressRing.setFraction((float) done / total);
        } else {
            progressRing.setIndeterminate(true);
        }

        StringBuilder line = new StringBuilder();
        line.append(total > 0 ? Ui.size(done) + " of " + Ui.size(total) : Ui.size(done));
        if (xferRate > 1024) {
            line.append("  ·  ").append(Ui.size((long) xferRate)).append("/s");
            if (total > done && xferRate > 0) {
                long eta = (long) ((total - done) / xferRate);
                line.append("  ·  ").append(formatEta(eta)).append(" left");
            }
        }
        progressLabel.setText(line.toString());
    }

    /** "5s" / "1m 20s" / "3h 4m" — coarse enough not to twitch, fine enough to trust. */
    private String formatEta(long seconds) {
        if (seconds < 60) {
            return Math.max(1, seconds) + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private void setProgressState(String word, int color) {
        if (progressState != null) {
            progressState.setText(word);
            progressState.setTextColor(color);
        }
    }

    /** A short buzz, if the device has a vibrator and the user has not turned haptics off. */
    private void vibrate(android.os.VibrationEffect effect) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(effect);
            }
        } catch (Throwable t) {
            Log.w(TAG, "vibrate failed", t);
        }
    }

    /** Two taps — a request for attention, for an incoming offer the person may not be watching. */
    private void hapticOffer() {
        vibrate(android.os.VibrationEffect.createWaveform(
                new long[] {0, 45, 90, 45}, new int[] {0, 190, 0, 190}, -1));
    }

    /** One light tick — a quiet acknowledgement that a transfer landed. */
    private void hapticDone() {
        vibrate(android.os.VibrationEffect.createOneShot(28, 140));
    }


    /**
     * Ask for the PIN shown on the RECEIVING device.
     *
     * A modal over everything, because that is what this interaction is: nothing is sent
     * until it is answered, and a field tucked into the progress card read as optional.
     * It is also not dismissible by tapping outside or by Back -- the only ways out are
     * the right digits or Cancel, both of which are decisions.
     *
     * We are not told our own copy of the PIN and must not be: if this screen could
     * display it, someone could confirm a transfer without ever looking at the other
     * device, and the PIN would be checking nothing. The daemon holds the value and
     * judges what is typed here.
     */
    private void askForPin(long id) {
        dismissPin();
        pinTransfer = id;
        pinCancelled = false;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, pad, pad, Ui.dp(this, 8));

        TextView title = Ui.text(this, "Check the PIN", 20, Ui.textColor(this), true);
        box.addView(title);

        pinMessage = Ui.text(this,
                "Enter the 4-digit code shown on the other device.", 14, Ui.textMuted(this), false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(this, 6);
        mp.bottomMargin = Ui.dp(this, 18);
        pinMessage.setLayoutParams(mp);
        box.addView(pinMessage);

        pinEntry = new EditText(this);
        pinEntry.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinEntry.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        pinEntry.setHint("0000");
        pinEntry.setTextSize(32);
        pinEntry.setGravity(Gravity.CENTER);
        pinEntry.setLetterSpacing(0.4f);
        // A pairing code, which the kit sets in mono for a reason that matters here more
        // than anywhere else: the code is being COMPARED against a second screen, digit by
        // digit, and proportional digits shift the four positions as each one is typed.
        pinEntry.setTypeface(android.graphics.Typeface.MONOSPACE);
        pinEntry.setTextColor(Ui.textColor(this));
        pinEntry.setHintTextColor(Ui.textFaint(this));
        pinEntry.setBackground(Ui.card(this, Ui.surfaceSunk(this), Ui.ruleColor(this), 14));
        pinEntry.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        // Four digits is the whole input, so submit as soon as they are there rather than
        // making someone reach for a button they have already earned.
        pinEntry.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) {
                if (e.length() == 4) {
                    submitPin();
                }
            }
        });
        box.addView(pinEntry);

        pinDialog = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("Cancel", (d, which) -> cancelPin())
                .setCancelable(false)
                .create();
        // Back must not dismiss it either: leaving the prompt without answering would
        // park the transfer with nothing on screen explaining why.
        pinDialog.setOnKeyListener((d, keyCode, event) ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK);
        pinDialog.show();
        pinEntry.requestFocus();
        pinDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /** Send what was typed to the daemon, which is the only side that knows the answer. */
    private void submitPin() {
        if (service == null || pinTransfer == 0) {
            return;
        }
        String typed = pinEntry.getText().toString().trim();
        boolean ok;
        try {
            ok = service.confirmTransferPin(pinTransfer, typed);
        } catch (Exception e) {
            Log.w(TAG, "could not submit the PIN", e);
            return;
        }
        if (ok) {
            pinTransfer = 0;
            dismissPin();
        } else {
            // A typo is the ordinary case, so this stays open rather than tearing the
            // transfer down and making them start again.
            pinMessage.setText("That does not match what the other device is showing.");
            pinEntry.setText("");
            pinEntry.requestFocus();
        }
    }

    /** Abandon the transfer from the PIN prompt. */
    private void cancelPin() {
        pinCancelled = true;
        long id = pinTransfer;
        pinTransfer = 0;
        dismissPin();
        if (service != null && id != 0) {
            try {
                service.cancelTransfer(id);
            } catch (Exception e) {
                Log.w(TAG, "could not cancel at the PIN prompt", e);
            }
        }
        showOutcome("Cancelled", "you stopped it before anything was sent");
    }

    private void dismissPin() {
        if (pinDialog != null) {
            try {
                pinDialog.dismiss();
            } catch (Exception ignored) {
                // Dismissing a dialog whose activity is gone is not worth a crash.
            }
            pinDialog = null;
        }
        dismissReceiverPin();
    }

    /**
     * Receiver-side: show the session code to read out to the sender.
     *
     * The PIN protects the SENDER ("am I sending to the right device?"), so it is shown
     * here on the receiver and spoken across, then typed on the sender. Cleared when the
     * transfer ends (dismissPin, from onTransferFinished).
     */
    private void showReceiverPin(String pin) {
        dismissReceiverPin();
        TextView code = Ui.text(this, pin == null ? "----" : pin, 34, Ui.accent(this), true);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.3f);
        int p = Ui.dp(this, 20);
        code.setPadding(p, p, p, p);
        pinShowDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Share code")
                .setMessage("Read this code to the sender so they know it is you:")
                .setView(code)
                .setPositiveButton("Done", null)
                .create();
        pinShowDialog.show();
    }

    private void dismissReceiverPin() {
        pendingReceiverPin = null;
        if (pinShowDialog != null) {
            try {
                pinShowDialog.dismiss();
            } catch (Exception ignored) {
            }
            pinShowDialog = null;
        }
    }

    /**
     * Report how a transfer ended, where the user is already looking.
     *
     * In send mode that is the files row, which is the only part of the screen that was
     * about this transfer. Previously an outcome went to the identity strip, which in
     * send mode is not even visible -- so a decline produced no feedback at all.
     */
    private void showOutcome(String title, String detail) {
        if (sendMode) {
            outcomeTitle = title;
            outcomeDetail = detail;
            render();
        } else {
            setIdentityState(title.toLowerCase(), false);
        }
    }

    private void hideProgress() {
        if (stage == Stage.TRANSFER || stage == Stage.DONE) {
            stage = Stage.IDLE;
            render();
        }
        // The prompt is modal and owns its own lifetime, but a transfer that ends while
        // it is up must not leave it there.
        dismissPin();
        pinTransfer = 0;
    }

    private void setIdentityState(String state, boolean live) {
        if (stateLine != null) {
            stateLine.setText(state);
        }
        if (liveDot != null) {
            liveDot.setLive(live, live ? Ui.live(this) : Ui.textFaint(this));
        }
    }

    /** The hero's headline — "Ready to receive", "Not receiving". Safe before it is built. */
    private void setHeroTitle(String title) {
        if (deviceLine != null) {
            deviceLine.setText(title);
        }
    }

    /**
     * What peers will see. The configured name if there is one, else the model.
     *
     * Mirrors the daemon's own fallback (persist.tarish.name, then ro.product.model) so the
     * screen cannot promise a name the daemon will not advertise.
     */
    private String deviceName() {
        String n = policy == null ? null : policy.deviceName;
        return n == null || n.trim().isEmpty() ? android.os.Build.MODEL : n.trim();
    }



    // -------------------------------------------------------------- modes ---

    @Override
    public void onModeChosen(boolean send) {
        if (send == sendMode) {
            return;
        }
        sendMode = send;
        // Ask first, then rebuild: render() replaces every view, so a state written
        // before it lands on views that are about to be thrown away.
        setDiscoverable(!send, "modeSwitch");
        render();
    }

    // ------------------------------------------------------------- service ---

    /**
     * Bind to the daemon, replacing a dead proxy if there is one.
     *
     * The daemon can restart -- it is a native service under init, and it does restart
     * on crash or upgrade. When it does, every proxy the client holds is permanently
     * dead: each call throws and no amount of retrying revives it. Catching the
     * exception and carrying on, which is what this did, meant the app looked alive and
     * silently did nothing until it was force-stopped and reopened.
     *
     * @return true if a usable binding exists afterwards
     */
    /** Re-read policy from preferences and managed configuration, and send it down. */
    /**
     * Flip one protocol between off and on, from its chip.
     *
     * ON means MODE_BOTH rather than restoring whatever the mode was before. Half-states
     * (receive-only, send-only) exist and are reachable from Settings, but a one-tap
     * control that lands on one of them cannot explain itself -- the chip would read "on"
     * while sending silently failed. Off/on here; nuance in Settings, one long-press away.
     *
     * Writes the USER preference and re-pushes. It never overrides a managed value: this
     * is only reachable when the field is unpinned, and PolicyStore.effective() would
     * discard it regardless.
     */
    private void toggleProtocol(int protocol) {
        if (policyStore == null) {
            policyStore = new PolicyStore(this);
        }
        boolean on = allowed(protocol);
        policyStore.setUserMode(PolicyStore.keyFor(protocol),
                on ? ITarishService.MODE_OFF : ITarishService.MODE_BOTH);
        pushPolicy();
        // The strip, the visibility line and the peer list all read policy.
        render();
        setDiscoverable(!sendMode, "protocolToggle");
    }

    private void pushPolicy() {
        if (policyStore == null) {
            policyStore = new PolicyStore(this);
        }
        policy = policyStore.effective();
        if (service == null) {
            return;
        }
        try {
            service.setPolicy(policy);
        } catch (Exception e) {
            Log.w(TAG, "could not push policy", e);
        }
    }

    private boolean connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            Log.e(TAG, "daemon did not publish " + SERVICE_NAME);
            service = null;
            return false;
        }
        service = ITarishService.Stub.asInterface(binder);
        try {
            service.registerCallback(callback);
            // The daemon starts DENIED and holds policy in memory, so it must be told
            // before anything else is asked of it -- including by a daemon that has just
            // restarted underneath us. Pushed on every connect rather than on change.
            pushPolicy();
            // Notice the daemon going away, rather than finding out on the next call
            // and treating it as an ordinary failure.
            binder.linkToDeath(deathRecipient, 0);
        } catch (Exception e) {
            Log.e(TAG, "could not register callback", e);
            service = null;
            return false;
        }
        // Re-assert the foreground after a reconnect. A daemon that has just
        // restarted has no idea we are on screen, and without this nothing would
        // tell it until the next onResume -- which, if the user never leaves the
        // app, may not come at all.
        //
        // Outside the try above on purpose: setActive has its own handling for an
        // older daemon, and must not be able to null out a binding that works.
        setActive(true);
        return true;
    }

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "daemon died — will rebind");
            service = null;
            // Do NOT rebind immediately: a service that has just died has not
            // re-registered yet, so the first attempt reliably finds nothing. The poll
            // loop retries until it is back, which also covers a daemon that takes a
            // while to come up.
            main.post(() -> setIdentityState("reconnecting\u2026", false));
        }
    };

    /**
     * Recover from a dead proxy discovered mid-call.
     *
     * linkToDeath is the fast path, but a call can still land on a proxy that has just
     * died, so every caller treats a failure as "reconnect and try once more".
     */
    private boolean reconnect() {
        Log.i(TAG, "rebinding to the daemon");
        service = null;
        return connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The appearance may have changed in Settings while this screen was stopped.
        // attachBaseContext only re-runs on a fresh instance, so rebuild to pick it up.
        if (Theme.mode(this) != themeMode) {
            recreate();
            return;
        }
        if (service == null) {
            connect();
        }
        startBeacon();
        // Cancel any pending restore: the user came back, so the radios stay as they are.
        main.removeCallbacks(restoreRadios);
        promptForRadiosIfNeeded();
        setActive(true);
        resumed = true;
        // Sampling the band once at resume is not enough -- see associationWatcher.
        registerReceiver(associationWatcher,
                new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED);
        // Re-read policy before acting on it: the user may have just come back from the
        // settings screen, or an administrator may have changed a managed value while
        // this activity was stopped. Both must take effect before we ask to be visible.
        pushPolicy();
        render();
        setDiscoverable(!sendMode, "onResume");
        main.post(poll);
        if (!sendMode) {
            collect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
        setDiscoverable(false, "onPause");
        // THE EXEMPTION IS FOR SOMEONE WHO IS PRESENT AND LOOKING AT IT, so leaving the
        // foreground closes it -- not the ten-minute timer running out later. The daemon
        // would close it anyway on its cap, and again at its own startup; this just makes
        // the common case immediate instead of eventual.
        AuthWindow.close(service);
        // The radio, unlike visibility, is released on leaving the foreground. The
        // daemon holds it for another half-minute so a file picker or a glance at
        // another app does not tear the link down and back up.
        resumed = false;
        try {
            unregisterReceiver(associationWatcher);
        } catch (IllegalArgumentException e) {
            // Not registered: onPause can follow a failed onResume. Not worth a crash.
        }
        setActive(false);
        stopService(new Intent(this, TarishBleService.class));
        askedAboutRadios = false;
        // Put back only what we turned on, and not for another half minute. Anything the
        // user already had on is never touched -- see Radios.
        if (radios.owesRestore()) {
            main.removeCallbacks(restoreRadios);
            main.postDelayed(restoreRadios, RADIO_RESTORE_DELAY_MS);
        }
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            try {
                service.unregisterCallback(callback);
            } catch (Exception e) {
                Log.w(TAG, "could not unregister callback", e);
            }
        }
        super.onDestroy();
    }

    /**
     * Tell the daemon whether we are in the foreground, which is what governs the
     * AWDL radio.
     *
     * Separate from setDiscoverable because the two genuinely differ: in Send mode
     * we are NOT discoverable and very much need the radio. Gating the link on
     * visibility would take it down under every outgoing transfer.
     */
    /**
     * The frequency Wi-Fi is associated on in MHz, 0 if it is not associated, or -1
     * if the adapter is off altogether. The three are different to the daemon: see the
     * band-memory note in the body.
     *
     * The daemons cannot answer this. Both are native, neither has framework access,
     * and the association frequency is not exposed as a file either could read -- so
     * the app is the only place it can come from, and it is why setActive carries it.
     *
     * It decides which BAND AWDL uses. The chip does 2.4 and 5 GHz simultaneously but
     * cannot hold two 5 GHz channels, so AWDL goes in the other band from Wi-Fi.
     * Getting this wrong drops the Wi-Fi association within about three seconds.
     */
    private int staFrequencyMhz() {
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            if (wm == null) {
                return 0;
            }
            // WI-FI OFF IS NEWS. NOT KNOWING IS NOT.
            //
            // The daemon remembers the last real frequency and treats 0 as "no news",
            // because on a chip where AWDL and Wi-Fi cannot coexist, raising AWDL
            // destroys the very association the value is read from -- and forgetting
            // the band there puts two devices on opposite ones.
            //
            // But a switched-off adapter is not that. There is no association to
            // protect and none coming back, so the memory is simply wrong, and it is
            // wrong in the expensive direction: it keeps AWDL in the OTHER band from a
            // network this device is not on. Measured on frankel with Wi-Fi off --
            // tarishd logged "Wi-Fi is on 5520 MHz -- putting AWDL in the other band,
            // [6]" and ran AirDrop on channel 6 at 0.87 MB/s, having refused 149 and 44
            // to protect an association that did not exist.
            //
            // isWifiEnabled() is the user's setting, not the link state, so it stays
            // true exactly through the case the memory is for. -1 says "off"; an older
            // daemon clamps it to 0 and behaves as it always did.
            if (!wm.isWifiEnabled()) {
                return -1;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return 0;
            }
            int f = info.getFrequency();
            return f > 0 ? f : 0;
        } catch (Exception e) {
            // Not fatal: the daemon treats 0 as "unknown" and keeps its previous choice.
            Log.w(TAG, "could not read the Wi-Fi frequency", e);
            return 0;
        }
    }

    /**
     * Re-report the Wi-Fi frequency whenever the ASSOCIATION changes.
     *
     * setActive() carries the band, and it used to be called only from onResume -- so the
     * frequency was sampled once, at whatever moment the screen happened to open. That is
     * the worst possible moment: AWDL coming up can itself drop the association, so the
     * app frequently resumed while Wi-Fi was down, read 0, and never corrected itself when
     * Wi-Fi came back.
     *
     * 0 means "unknown", and tarishd then GUESSES the band. staFrequencyMhz() says what a
     * wrong guess costs: the chip cannot hold two 5 GHz channels, so AWDL must go in the
     * other band, and getting it wrong "drops the Wi-Fi association within about three
     * seconds" -- which resumes the loop. Seen on hardware: blazer sat at sta_freq=0 with
     * Wi-Fi demonstrably associated on 5520 MHz.
     *
     * WIFI_STATE_CHANGED_ACTION is not the one to watch, and Radios already has it: that
     * fires for the adapter being switched on and off, not for joining a network or
     * roaming to a different band. NETWORK_STATE_CHANGED_ACTION is the association.
     */
    private final BroadcastReceiver associationWatcher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Only while foreground: setActive(false) on pause told the daemon we are
            // gone, and re-asserting active here would quietly undo that.
            if (resumed) {
                setActive(true);
            }
        }
    };

    /** True between onResume and onPause. Gates associationWatcher. */
    private boolean resumed;

    /**
     * Start the BLE beacon, and do not die if the system says we are background.
     *
     * TarishBleService is a PLAIN service on purpose -- the manifest calls it a privacy
     * position: it runs between onResume and onPause and nothing advertises from boot.
     * The consequence is that startService() throws BackgroundServiceStartNotAllowedException
     * whenever the system still counts this uid as background, which happens even from
     * onResume: an activity can be resumed a moment before the process is reclassified,
     * and `am start` returns well before that.
     *
     * Unhandled, that exception escapes onResume and KILLS THE APP. Seen on both devices,
     * repeatedly, at bg:+3m6s and bg:+11m0s -- and it takes Quick Share with it, because
     * this service owns the BLE endpoint advertisement. Two Tarish devices then never
     * discover each other, while a peer that advertises independently (a Windows desktop)
     * is still found, which makes it look like a peer problem rather than ours.
     *
     * Refusing to start is the CORRECT outcome in that state, so the only bug was treating
     * it as fatal. onResume runs again on the next foreground transition and the beacon
     * comes up then.
     */
    private void startBeacon() {
        try {
            startService(new Intent(this, TarishBleService.class));
        } catch (IllegalStateException e) {
            // BackgroundServiceStartNotAllowedException extends IllegalStateException;
            // catching the supertype also covers the pre-API-31 spelling.
            Log.w(TAG, "beacon not started — system considers us background", e);
        }
    }

    private void setActive(boolean active) {
        if (service == null) {
            return;
        }
        try {
            service.setActive(active, staFrequencyMhz());
        } catch (Exception e) {
            // An older daemon does not have this method. That is survivable: it
            // simply keeps the radio up, which is what it did before this existed.
            Log.w(TAG, "setActive unavailable", e);
        }
    }

    /**
     * Is the AWDL transport actually up?
     *
     * Worth asking separately from anything else, because when it is down every other
     * screen is truthful and useless: no peers, not discoverable, no error. The daemon
     * refuses to bring the radio up without a regulatory country, and a phone that has
     * never joined a Wi-Fi network and has no SIM has none -- which presents as the app
     * simply not working, with nothing anywhere saying why.
     */
    private boolean transportUp() {
        if (service == null) {
            return false;
        }
        try {
            TarishStatus st = service.getStatus();
            return st != null && st.linkUp;
        } catch (Exception e) {
            return false;
        }
    }

    /** Update the hero's "<name> · m:ss left" line in place. Safe if the view isn't built. */
    private void updateVisibleCountdown(long remainingMs) {
        if (visibleCountdownView == null) {
            return;
        }
        // ONLY WHILE ACTUALLY DISCOVERABLE.
        //
        // The countdown shares the hero's subtitle with every other state, and the poll tick
        // fires regardless of which one is showing. Without this guard it overwrote them and
        // the screen contradicted itself -- seen on 2026092323, immediately after a flash
        // while the AWDL radio was still coming up:
        //
        //     Cannot receive
        //     Pixel 10 Pro · 9:52 left
        //
        // A countdown under "cannot receive" is the same class of lie as a hero that pulses
        // when nothing is listening. The state branches own this line; the tick only refines
        // it when their answer was "ready".
        //
        // GUARDED ON heroReady, NOT on `discoverable`. A first attempt used `discoverable`
        // and still showed the contradiction, because the two are not the same question:
        // for ~10s after launch the app is discoverable while the AWDL link is still coming
        // up, so the title said "Cannot receive" and the countdown wrote underneath it
        // anyway. Only the branch that rendered "Ready to receive" may hand this line over.
        if (!heroReady) {
            return;
        }
        long s = Math.max(0, remainingMs / 1000);
        visibleCountdownView.setText(String.format(java.util.Locale.US,
                // The NAME, then the time. "What will they see?" and "for how long?"
                // are the two questions this line exists to answer, and the name is the
                // one a person cannot work out -- it may be a configured name, not the model.
                "%s · %d:%02d left", deviceName(), s / 60, s % 60));
    }

    private void setDiscoverable(boolean visible, String why) {
        // Do not ask for something policy forbids. The daemon would refuse anyway --
        // it is the enforcement point -- but a refusal here looks like a dead binder to
        // the caller below and triggers a pointless rebind. Turning visibility OFF is
        // never gated: policy restricts sharing, never the ability to stop.
        //
        // policy.airdrop, and NOT allowed(...), on purpose: the POLICY GATE below is
        // AirDrop-specific -- whether this device may be an AirDrop receiver at all.
        //
        // The VISIBILITY it then sets is not. It used to be: this call governed AirDrop's
        // mDNS advertisement and the httpd answering /Discover, while Quick Share went on
        // advertising over LAN mDNS from the daemon and over BLE from TarishBleService,
        // neither of which consulted it. So "not discoverable" covered one protocol of two
        // and the UI said something untrue. Both are now driven from here.
        if (visible && policy != null && !PolicyStore.allowsReceive(policy.airdrop)) {
            Log.i(TAG, "setDiscoverable(true) skipped from " + why + " — policy denies receive");
            return;
        }
        Log.i(TAG, "setDiscoverable(" + visible + ") from " + why + " sendMode=" + sendMode);

        // THE BLE BEACON IS OURS, NOT THE DAEMON'S, so it has to be told separately.
        // Before the daemon call and outside its null check on purpose: the beacon does not
        // depend on the binder being up, and going invisible must still work when the
        // daemon is being rebound. Only the advertisement is affected -- scanning keeps
        // running, because that is how SENDING finds peers and a sender is not making
        // itself visible.
        try {
            startService(new Intent(this, TarishBleService.class)
                    .setAction(TarishBleService.ACTION_VISIBILITY)
                    .putExtra(TarishBleService.EXTRA_VISIBLE, visible));
        } catch (IllegalStateException e) {
            // Same background-start rule as startBeacon(): refusing is correct in that
            // state, and an exception escaping here would kill the app.
            Log.w(TAG, "could not reach the beacon to set visibility", e);
        }
        if (service == null) {
            setIdentityState("tarish service unavailable", false);
            return;
        }
        try {
            service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
            discoverable = visible;
            if (visible) {
                visibleSince = System.currentTimeMillis();
            }
            if (!sendMode) {
                setIdentityState(
                        visible ? "visible to everyone nearby — screen stays on"
                                : "not visible",
                        visible);
            }
        } catch (Exception e) {
            Log.w(TAG, "setDiscoverable failed, rebinding", e);
            if (reconnect()) {
                try {
                    service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
                    discoverable = visible;
                    if (visible) {
                        visibleSince = System.currentTimeMillis();
                    }
                    setIdentityState(
                            visible ? "visible to everyone nearby — screen stays on"
                                    : "not visible",
                            visible);
                    return;
                } catch (Exception again) {
                    Log.e(TAG, "still cannot reach the daemon", again);
                }
            }
            setIdentityState("cannot reach the tarish service", false);
        }
    }

    private void collect() {
        if (service == null) {
            return;
        }
        List<FileCollector.Stored> got = FileCollector.collectAll(this, service);
        if (got.isEmpty()) {
            return;
        }
        // The files carry their own names and sizes; the peer is whoever last offered.
        activity.add(TransferRecord.received(offerFrom, offerProtocol, got));
        renderInbox();
    }

    // ---------------------------------------------------------------- send ---

    private void collectShared(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (u != null) {
                shared.add(u);
            } else {
                // Shared text (a selection, a URL, a note) rather than a file. Apple has no
                // "text item" over AirDrop that Android can hand it, so we stage it as a
                // small .txt -- which an iPhone or Mac receives as a plain text file.
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text != null && text.length() > 0) {
                    Uri note = writeNote(text.toString());
                    if (note != null) {
                        shared.add(note);
                    }
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> us = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (us != null) {
                shared.addAll(us);
            }
        }
    }

    /**
     * Stage arbitrary text as a small .txt in the app cache, returning a URI the send path
     * can open. UTF-8, no BOM -- an outbound file should be clean text; a BOM is added only
     * on the receive side, where Android editors need the hint.
     */
    private Uri writeNote(String text) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "notes");
            dir.mkdirs();
            pruneNotes(dir);
            java.io.File f = new java.io.File(dir, noteFileName());
            try (java.io.OutputStream os = new java.io.FileOutputStream(f)) {
                os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return Uri.fromFile(f);
        } catch (Exception e) {
            Log.w(TAG, "could not stage a note", e);
            return null;
        }
    }

    /**
     * "Note-a7k2p.txt". Fixed prefix, five random characters, and NOTHING from the text.
     *
     * This used to name the file after the note's first line, sanitised and capped, so the
     * receiver saw something meaningful rather than "note.txt" every time. That reads as a
     * nicety and is actually a disclosure: a filename is shown on the RECEIVING device --
     * in the AirDrop prompt and in the Quick Share notification -- BEFORE anyone there has
     * accepted anything. So the first line of a note reached a screen we do not control,
     * belonging to a peer who may have declined, or who may not be the person we meant.
     * Point a phone at the wrong device and the subject line has already left.
     *
     * The sanitiser was not the weak part, and replacing it with a stricter one would have
     * missed the point. It did block traversal and control characters -- everything outside
     * [letter digit space - _] became a space, so no dot, slash or bidi override survived --
     * but `Character.isLetterOrDigit` is Unicode-aware by design, so the whole of any script
     * passed through it intact. It was doing its job. The content simply does not belong in
     * the name.
     *
     * Random rather than a counter or a timestamp: a counter says how many notes have been
     * sent and a timestamp says when, and neither is anyone else's business either.
     */
    private String noteFileName() {
        final String alphabet = "abcdefghijkmnopqrstuvwxyz23456789";   // no l/1, no o/0
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder("Note-");
        for (int i = 0; i < 5; i++) {
            b.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return b.append(".txt").toString();
    }

    /**
     * Drop staged notes older than an hour.
     *
     * Content-derived names used to collide and overwrite, which kept this directory small
     * by accident. Random names do not, so the sweep has to be deliberate. An hour is far
     * longer than any transfer, so this never removes a file being read; it is cache
     * hygiene, not part of the transfer's lifecycle.
     */
    private void pruneNotes(java.io.File dir) {
        java.io.File[] old = dir.listFiles();
        if (old == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 3600_000L;
        for (java.io.File f : old) {
            if (f.isFile() && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    /** Type or paste text and stage it as a note; the send screen then lists peers for it. */
    private void composeNote() {
        EditText in = new EditText(this);
        in.setHint("Type or paste text to send as a note");
        in.setGravity(Gravity.TOP | Gravity.START);
        // MULTI_LINE is what makes Enter insert a newline. setMinLines() alone only made the
        // box four lines TALL: an EditText built in code defaults to a single-line input type,
        // so the Enter key ran an IME action and the field stayed one paragraph however big it
        // looked. Pasting multi-line text worked, typing it did not.
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.setSingleLine(false);
        in.setMinLines(4);
        // Grow to ten lines, then scroll inside the dialog rather than pushing the buttons
        // off the bottom of the screen.
        in.setMaxLines(10);
        in.setVerticalScrollBarEnabled(true);
        in.setTextColor(Ui.textColor(this));
        in.setHintTextColor(Ui.textFaint(this));
        // Prefill from the clipboard -- "copy from any app and send it" is the whole point.
        CharSequence clip = clipboardText();
        if (clip != null) {
            in.setText(clip);
        }
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), 0);
        box.addView(in);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Send a note")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (d, w) -> {
                    String t = in.getText().toString();
                    if (t.trim().isEmpty()) {
                        return;
                    }
                    Uri staged = writeNote(t);
                    if (staged != null) {
                        shared.clear();
                        shared.add(staged);
                        outcomeTitle = null;
                        outcomeDetail = null;
                        render();   // the send screen now lists peers to send the note to
                    }
                })
                .show();
    }

    /** The clipboard's text, or null. The app is foreground here, so the read is allowed. */
    private CharSequence clipboardText() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                return t != null && t.length() > 0 ? t : null;
            }
        } catch (Throwable ignored) {
            // No clipboard access is not an error; the field just starts empty.
        }
        return null;
    }

    /**
     * Open the system picker.
     *
     * OPEN_DOCUMENT rather than GET_CONTENT: it returns a persistable URI the daemon can
     * still read after the picker is gone, which matters because the transfer outlives
     * this screen.
     */
    private void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Exception e) {
            Log.e(TAG, "no document picker available", e);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_PICK || result != RESULT_OK || data == null) {
            return;
        }
        outcomeTitle = null;
        outcomeDetail = null;
        shared.clear();
        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                shared.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            shared.add(data.getData());
        }
        render();
    }

    /** The first couple of filenames, so the row says what is actually going. */
    private String firstNames() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < shared.size() && i < 2; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(displayName(shared.get(i)));
        }
        if (shared.size() > 2) {
            b.append(" +").append(shared.size() - 2);
        }
        return b.toString();
    }

    private String describeShared() {
        if (shared.isEmpty()) {
            return "nothing chosen";
        }
        return shared.size() == 1 ? "1 file ready" : shared.size() + " files ready";
    }

    /**
     * What an incoming transfer is, for the notification.
     *
     * The file names rather than a count when there is one of them, because "Receiving
     * report.pdf" tells you whether to care and "Receiving 1 file" does not.
     */
    /**
     * What an outgoing transfer is, for the notification.
     *
     * Not `describeShared`, which is phrased for the picker -- "Sending 3 files ready"
     * reads like a bug.
     */
    private String describeSending() {
        if (shared.isEmpty()) {
            return "";
        }
        return shared.size() == 1 ? displayName(shared.get(0)) : shared.size() + " files";
    }

    private String describeOffer() {
        if (offerNames == null || offerNames.length == 0) {
            return "";
        }
        return offerNames.length == 1
                ? offerNames[0]
                : offerNames.length + " files";
    }

    private void refreshPeers() {
        if (service == null || peerBox == null) {
            return;
        }
        // DO NOT LIST DEVICES THAT CANNOT BE SENT TO.
        //
        // Discovery keeps running for its own reasons, so peers found before sending was
        // turned off stayed on screen, stayed tappable, and failed at the last step --
        // the daemon refuses sendFiles, correctly, but by then the person has chosen a
        // device and picked files. Offering an action that is known to be refused is the
        // wrong place to enforce a policy.
        //
        // PER PROTOCOL, and that distinction is the whole point. This tested
        // policy.allowsSend(policy.airdrop) and cleared the ENTIRE list, so switching
        // AirDrop off -- which is what a BCM4383 device wants, to stop AWDL taking Wi-Fi
        // down -- also emptied the Quick Share list and reported it as sending being off.
        // Peers are filtered by their own protocol below; the list only goes away when
        // neither protocol may send, which blockedNotice explains.
        if (!allowed(ITarishService.PROTOCOL_AIRDROP)
                && !allowed(ITarishService.PROTOCOL_QUICKSHARE)) {
            if (!"blocked".equals(peerSignature)) {
                peerSignature = "blocked";
                peerBox.removeAllViews();
            }
            return;
        }
        TarishPeer[] peers;
        try {
            peers = service.getPeers();
        } catch (Exception e) {
            // Almost certainly a dead proxy from a daemon restart. Rebind and let the
            // next tick populate; failing silently here is what made the list stay
            // empty until the app was restarted by hand.
            Log.w(TAG, "getPeers failed, rebinding", e);
            reconnect();
            return;
        }
        // A peer whose "name" is still the raw 12-hex mDNS identifier has not answered
        // /Discover yet, and a device that will not answer cannot accept a file either.
        // Showing the hex string as though it were a device name is what made the list
        // look broken -- it is not a name, it is the absence of one.
        List<TarishPeer> named = new ArrayList<>();
        for (TarishPeer p : peers) {
            if (p.name == null || p.name.matches("[0-9a-f]{12}")) {
                continue;
            }
            // A peer found by a protocol this device may not send over is not actionable,
            // and the tile would fail at sendFiles. Dropped here rather than at the tap.
            if (!allowed(p.protocol)) {
                continue;
            }
            named.add(p);
        }
        peers = named.toArray(new TarishPeer[0]);

        // Everything the tiles are drawn FROM belongs in the signature, not just the
        // peers -- the gate below reads `shared`, so a selection change with an
        // unchanged peer list must still redraw.
        // Order-INSENSITIVE, because this compares a SET. Appending in array order made
        // the signature depend on the order getPeers happened to return, which was a
        // HashMap's -- random per call. So the gate never held: the tiles were torn down
        // and rebuilt on every poll, peers juggled on screen, and a tile could be
        // replaced between a finger going down and the tap landing.
        //
        // The daemon now sorts too. This stays sorted anyway: the gate is what protects
        // the live views and their listeners, and it should not depend on a promise made
        // by the other side of an IPC boundary.
        List<String> rows = new ArrayList<>();
        for (TarishPeer p : peers) {
            rows.add(p.id + "|" + p.name);
        }
        Collections.sort(rows);
        StringBuilder sig = new StringBuilder(shared.isEmpty() ? "gated\n" : "live\n");
        // Policy is part of what the tiles are drawn from: switching a protocol off
        // changes which peers belong on screen, and without this the unchanged peer set
        // matched the old signature and the tiles were left as they were.
        sig.append(allowed(ITarishService.PROTOCOL_AIRDROP) ? "a1" : "a0")
                .append(allowed(ITarishService.PROTOCOL_QUICKSHARE) ? "q1" : "q0")
                .append('\n');
        for (String r : rows) {
            sig.append(r).append('\n');
        }
        if (sig.toString().equals(peerSignature)) {
            return;   // nothing changed; leave the views (and their listeners) alone
        }
        peerSignature = sig.toString();

        peerBox.removeAllViews();
        if (peers.length == 0) {
            TextView none = Ui.text(this,
                    "Looking…\nOn an Apple device, open AirDrop and set it to Everyone",
                    13, Ui.textFaint(this), false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 14));
            peerBox.addView(none);
            return;
        }
        // A row of tiles, wrapping every three, which is how the reference lays peers out.
        LinearLayout row = null;
        for (int i = 0; i < peers.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                peerBox.addView(row);
            }
            TarishPeer p = peers[i];
            LinearLayout tile = Ui.deviceTile(this, glyphFor(p), p.name, kindOf(p));
            if (shared.isEmpty()) {
                // Dimmed and inert. A tile that looks tappable and silently does
                // nothing is worse than one that plainly cannot be used -- tapping it
                // and getting no response reads as the app being broken.
                tile.setAlpha(0.35f);
                tile.setOnClickListener(v -> nudgeChooseFiles());
            } else {
                tile.setAlpha(1f);
                tile.setOnClickListener(v -> sendTo(p));
            }
            row.addView(tile, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /**
     * The device glyph, chosen from what the peer actually told us and nothing else.
     *
     * <p>AirDrop peers carry no device type at all: the mDNS TXT record holds only
     * {@code flags}, and /Discover answers with a name. So an Apple peer gets the general
     * devices mark, which is the honest answer rather than a guess dressed as detail.
     *
     * <p>Quick Share peers DO advertise a type — three bits in the first byte of the
     * endpoint info — and the daemon decodes it. It does not yet forward it: {@code model}
     * arrives empty for both protocols today (see main.rs, where TarishPeer is built), so
     * this reads a field that is currently always blank. That is deliberate. The mapping
     * belongs on the UI side, and when the daemon fills {@code model} in the icons become
     * accurate with no change here. Do not invent a type from the peer's NAME to fill the
     * gap — "K-N6" and "K-ProArt" are what a person typed, not what the device is.
     */
    private static Glyph.Kind glyphFor(TarishPeer p) {
        String model = p.model == null ? "" : p.model.toLowerCase(java.util.Locale.ROOT);
        if (model.contains("phone") || model.contains("foldable")) {
            return Glyph.Kind.PHONE;
        }
        return Glyph.Kind.DEVICE;
    }

    /** The protocol that found this peer, named for a person rather than for a log. */
    private static String kindOf(TarishPeer p) {
        return p.protocol == ITarishService.PROTOCOL_QUICKSHARE ? "Quick Share" : "AirDrop";
    }

    private void cancelActive() {
        // NEVER RETURN FROM HERE IN SILENCE.
        //
        // This used to `return` on activeTransfer == 0 with no log and no UI change, which
        // is indistinguishable from a dead button -- and on Quick Share sends it WAS dead,
        // for the whole transfer, because the id arrived only after send() finished. That
        // is fixed at the callsite; what is left is the genuinely un-cancellable window
        // before the daemon has issued an id at all (BLE discovery, RFCOMM connect), and
        // the person deserves to be told rather than left tapping.
        if (service == null) {
            Log.w(TAG, "cancel: no service");
            progressLabel.setText("Cannot cancel — not connected");
            return;
        }
        if (activeTransfer == 0) {
            Log.w(TAG, "cancel: no transfer id yet (still connecting) — nothing to cancel");
            progressLabel.setText("Still connecting — cannot cancel yet");
            return;
        }
        try {
            service.cancelTransfer(activeTransfer);
            progressLabel.setText("Cancelling…");
        } catch (Exception e) {
            Log.e(TAG, "cancelTransfer failed", e);
            progressLabel.setText("Cancel failed");
        }
    }

    /**
     * Say why a device cannot be picked yet, and offer the way out.
     *
     * Reached by tapping a dimmed tile. The dimming states it; this explains it for
     * anyone who tries anyway.
     */
    private void nudgeChooseFiles() {
        outcomeTitle = "Choose files first";
        outcomeDetail = "pick something to send, then tap a device";
        render();
    }

    private void sendTo(TarishPeer peer) {
        Log.i(TAG, "sendTo " + peer.name + " with " + shared.size() + " file(s)");
        if (service == null) {
            Log.w(TAG, "no service");
            return;
        }
        if (shared.isEmpty()) {
            Log.w(TAG, "nothing chosen");
            return;
        }
        List<ParcelFileDescriptor> fds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Uri uri : shared) {
            try {
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    fds.add(pfd);
                    names.add(displayName(uri));
                }
            } catch (Exception e) {
                Log.e(TAG, "could not open " + uri, e);
            }
        }
        if (fds.isEmpty()) {
            showOutcome("Could not read the files", "check the files you chose");
            return;
        }
        // Open the review card on the peer we are about to dial. The bar runs indeterminate
        // through the connect/handshake; the daemon's first progress callback flips it to a
        // real fraction with speed and ETA.
        beginTransfer(peer.name, describeSending(), peer.protocol, true,
                names.toArray(new String[0]));
        // On send we hold the file URIs, so a single image can carry a real thumbnail
        // instead of the type mark -- the closest thing to what AirDrop shows.
        maybeLoadSendThumbnail();

        // QUICK SHARE IS HANDLED BEFORE THE try/finally BELOW, NOT INSIDE IT.
        //
        // That block closes the descriptors in `finally`, and `finally` runs on `return`
        // -- so branching to a background thread from inside it closed every fd the
        // instant the thread started using them. The daemon reported "Bad file
        // descriptor" from writeToParcel, after the Bluetooth connection had already
        // succeeded, which reads like a transport failure and is not one.
        //
        // The sending thread owns these descriptors and closes them itself.
        if (peer.protocol == ITarishService.PROTOCOL_QUICKSHARE) {
            // Quick Share needs a connection this process opens. The daemon cannot
            // reach framework Bluetooth, so it cannot dial a peer itself -- it runs
            // the protocol on a socket we hand it. Done off the UI thread because
            // an RFCOMM connect blocks, and against an absent peer it blocks for
            // seconds.
            final ParcelFileDescriptor[] toSend = fds.toArray(new ParcelFileDescriptor[0]);
            final String[] toName = names.toArray(new String[0]);
            final ITarishService svc = service;
            new Thread(() -> {
                // ADOPT THE ID THE MOMENT THE DAEMON ISSUES IT, not when send() returns.
                //
                // QuickShareSender.send() blocks for the WHOLE transfer, so its return value
                // arrives only once everything is over. Setting activeTransfer from there
                // left it 0 for the entire transfer, and cancelActive() gives up on
                // activeTransfer == 0 -- so Cancel was dead for every Quick Share send, in
                // silence. The callback fires before the first byte moves.
                long id = QuickShareSender.send(svc, peer, toSend, toName,
                        started -> main.post(() -> {
                            activeTransfer = started;
                            // From here the transfer must survive this screen. It runs
                            // partly in THIS process -- we own the Bluetooth socket and pump
                            // bytes through it -- so a backgrounded app being killed takes
                            // the socket with it.
                            TransferService.watch(getApplicationContext(), started,
                                    describeSending(), true);
                        }));
                main.post(() -> {
                    if (id == 0) {
                        showOutcome("Could not reach " + peer.name, "the device did not answer");
                    }
                });
                for (ParcelFileDescriptor pfd : toSend) {
                    try {
                        pfd.close();
                    } catch (Exception ignored) {
                        // Sent or failed; nothing useful to do.
                    }
                }
            }, "tarish-qs-send").start();
            return;
        }

        try {
            activeTransfer = service.sendFiles(peer.id,
                    fds.toArray(new ParcelFileDescriptor[0]),
                    names.toArray(new String[0]));
            // The daemon owns this one end to end, so it would survive us regardless --
            // but the person still deserves to see it progressing after they leave.
            TransferService.watch(getApplicationContext(), activeTransfer,
                    describeSending(), true);
        } catch (Exception e) {
            Log.e(TAG, "sendFiles failed", e);
            showOutcome("Could not send", "the transfer did not start");
        } finally {
            // The daemon duplicates what it needs, so these are ours to close.
            for (ParcelFileDescriptor pfd : fds) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                    // Already sent or already failed; nothing useful to do.
                }
            }
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no display name for " + uri, e);
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "file" : last;
    }

    /**
     * Offer to turn on whatever Tarish needs, if anything is off.
     *
     * Asked at most once per foreground visit: a user who says no should be able to look
     * around the app without being nagged, and the answer is obvious enough from the empty
     * peer list. Saying yes is remembered only for as long as it takes to put it back.
     */
    /**
     * Drop everything we think we know about peers and browse again.
     *
     * Deliberately clears the local view too rather than waiting for the next poll:
     * a refresh that leaves the stale rows on screen for a second reads as "nothing
     * happened", and the user taps it again.
     */
    private void forceRediscover() {
        if (service == null) {
            Log.w(TAG, "refresh: not connected to the daemon");
            return;
        }
        try {
            service.refreshPeers();
            peerSignature = null;       // force the next poll to rebuild the rows
            if (peerBox != null) {
                peerBox.removeAllViews();
                TextView searching = Ui.text(this, "Searching\u2026", 12, Ui.textFaint(this), false);
                searching.setGravity(Gravity.CENTER);
                searching.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
                peerBox.addView(searching);
            }
            Log.i(TAG, "refresh: asked the daemon to re-browse");
        } catch (Exception e) {
            Log.w(TAG, "refresh failed", e);
        }
    }

    private void promptForRadiosIfNeeded() {
        if (askedAboutRadios || !radios.anythingOff()) {
            return;
        }
        askedAboutRadios = true;
        final String off = radios.whatIsOff();
        new AlertDialog.Builder(this)
                .setTitle("Turn on " + off + "?")
                .setMessage("Tarish needs " + off + " to find nearby devices. "
                        + "If you had it off, Tarish turns it back off when you leave.")
                .setPositiveButton("Turn on", (d, w) -> {
                    if (!radios.enableAll()) {
                        // Do not fail silently. Failing silently with Wi-Fi off is the
                        // exact bug this path exists to fix, and a refusal here means
                        // something is wrong with our privileges, not with the user.
                        Log.w(TAG, "could not turn on " + off);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Could not turn on " + off)
                                .setMessage("Turn it on from Settings, then come back.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }
}
