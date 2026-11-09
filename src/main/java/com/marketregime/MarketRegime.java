package com.marketregime;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.TickAttrib;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.UnrestrictedData;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.messages.indicators.WidgetDisplayInfo;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.AxisGroup;
import velox.api.layer1.simplified.BackfilledDataListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.IndicatorModifiable;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.IntervalListener;
import velox.api.layer1.simplified.Intervals;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.api.layer1.simplified.WidgetRules;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;

/**
 * Régime de marché intraday sous le graphique de l'instrument affiché dans Bookmap, connecté à TWS
 * API en streaming. La référence du régime est l'instrument du graphique lui-même (NQ, MNQ, ES,
 * une action...) : sa performance depuis 09h30 NY est mesurée sur ses propres trades Bookmap.
 *
 * <ul>
 *   <li>Sur la Heatmap ({@link GraphType#PRIMARY}) : courbes continues depuis l'ouverture RTH
 *       (09h30 New York) du panier Risk (9 actions), du panier Refuge (7 actions) et du panier
 *       Mega-cap (5 actions pondérées), ancrées sur le prix 09h30 de l'instrument du graphique.
 *   <li>Dans le Bottom Chart et le panneau des Widgets ({@link GraphType#BOTTOM}) : Market Regime
 *       (de -2 à +2, coloré dynamiquement selon le régime actif), écart Refuge − Risk (%),
 *       variations directes en % (Chart, Risk, Refuge, Mega-cap) et corrélations Cross / Within.
 * </ul>
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Market Regime")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@UnrestrictedData
public class MarketRegime
    implements CustomModule,
        TradeDataListener,
        TimeListener,
        IntervalListener,
        HistoricalModeListener,
        // Reçoit aussi l'historique cloud (backfill) de l'instrument pour connaître son vrai 09h30
        BackfilledDataListener,
        CustomSettingsPanelProvider {

  private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
  private static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
  private static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

  // Tailles maximales des paniers : 30 + 29 + 5 = 64 lignes TWS sur les 100 par défaut d'un compte
  // (mêmes plafonds que Quant Research et l'indicateur TradingView)
  static final int MAX_RISK = 30;
  static final int MAX_REFUGE = 29;
  static final int MAX_MEGA = 5;
  private static final int MIN_BASKET_STOCKS = 5;
  private static final int MIN_MEGA_STOCKS = 3;
  private static final int MIN_CORR_STEPS = 3;

  private static final double MIN_ACTIVE_SHARE = 0.5;
  private static final double MAX_MEGA_CHART_GAP = 0.20;
  private static final double PERF_HYSTERESIS = 0.10;
  private static final double SPREAD_EXIT_RATIO = 0.8;

  // Courbes Heatmap (index 0..2) et performance de l'instrument (index 3) dans heatmapPerfPoints
  private static final int HEATMAP_LINES = 3;
  private static final int PERF_CHART = 3;
  private static final int MIN_AUTO_SCALE_STEPS = 10;
  private static final double MIN_HEATMAP_SCALE = 0.05;
  private static final double MAX_HEATMAP_SCALE = 2.0;
  private static final double AUTO_SCALE_REDRAW_RATIO = 0.05;

  private static final int TICKER_RISK_BASE = 200;
  private static final int TICKER_REF_BASE = 300;
  private static final int TICKER_MEGA_BASE = 400;
  private static final int HIST_REQ_OFFSET = 1000;
  private static final int HIST_ID_RANGES = 8;

  // Pacing TWS : au plus (lignes de données / 2) = 50 requêtes par seconde par défaut ; une requête
  // toutes les 25 ms en envoie 40 par seconde
  private static final long REQUEST_SPACING_MS = 25L;

  // Historique du jour redemandé à 09h31:05 NY, une fois la première barre 1m de la séance close
  private static final LocalTime HIST_REQUEST_AFTER_OPEN = LocalTime.of(9, 31, 5);
  private static final int MAX_HIST_RETRIES = 3;

  // Tick types TWS API (com.ib.client.TickType)
  private static final int FIELD_LAST = 4;
  private static final int FIELD_OPEN = 14;
  private static final int FIELD_DELAYED_LAST = 68;
  private static final int FIELD_DELAYED_OPEN = 76;

  // Couleurs par défaut alignées sur market regime.pine
  private static final Color DEFAULT_CHART_COLOR = new Color(0xFF, 0xEB, 0x3B);
  private static final Color DEFAULT_RISK_COLOR = new Color(0x29, 0x62, 0xFF);
  private static final Color DEFAULT_REFUGE_COLOR = new Color(0xFF, 0x98, 0x00);
  private static final Color DEFAULT_MEGA_COLOR = new Color(0x9C, 0x27, 0xB0);
  private static final Color DEFAULT_SPREAD_COLOR = new Color(0xDB, 0xDB, 0xDB);
  private static final Color DEFAULT_CROSS_COLOR = new Color(0x26, 0xC6, 0xDA);
  private static final Color DEFAULT_WITHIN_COLOR = new Color(0xAB, 0x47, 0xBC);

  private static final Color DEFAULT_NEUTRAL_COLOR = new Color(0x78, 0x7B, 0x86);
  private static final Color DEFAULT_LIQUIDATION_COLOR = new Color(0xF2, 0x36, 0x45);
  private static final Color DEFAULT_SLOW_LONG_COLOR = new Color(0x08, 0x99, 0x81);
  private static final Color DEFAULT_ROT_REFUGE_COLOR = new Color(0xFF, 0x98, 0x00);
  private static final Color DEFAULT_ROT_RISK_COLOR = new Color(0x29, 0x62, 0xFF);

  private static final int TEXT_INDENT = 10;
  private static final String VERSION = readVersion();

  /** États possibles du régime de marché intraday, avec leur score symétrique (-2 à +2). */
  enum Regime {
    ROTATION_RISK("Rotation to risk", 2.0),
    SLOW_LONG("Slow long", 1.0),
    NEUTRAL("Neutral", 0.0),
    ROTATION_REFUGE("Rotation to refuge", -1.0),
    LIQUIDATION("Liquidation", -2.0);

    final String label;
    final double score;

    Regime(String label, double score) {
      this.label = label;
      this.score = score;
    }
  }

  /** Réglages persistés avec le workspace Bookmap. */
  @StrategySettingsVersion(
      currentVersion = 1,
      compatibleVersions = {})
  public static class Settings {
    /** Hôte TWS ou IB Gateway. */
    public String twsHost = "127.0.0.1";

    /** Port socket TWS (7497 Paper / 7496 Live) ou IB Gateway (4002 / 4001). */
    public int twsPort = 7497;

    /** Identifiant client TWS API. */
    public int twsClientId = 17;

    /** Symboles du panier Risk (5 à 30 actions), copiés de la dernière watchlist chargée. */
    public String[] riskSymbols = new String[0];

    /** Symboles du panier Refuge (5 à 29 actions), copiés de la dernière watchlist chargée. */
    public String[] refugeSymbols = new String[0];

    /** Symboles du panier Mega-cap (3 à 5 actions), copiés de la dernière watchlist chargée. */
    public String[] megaSymbols = new String[0];

    /** Poids des actions du panier Mega-cap (capitalisation en T$), même ordre que les symboles. */
    public double[] megaWeights = new double[0];

    /** Fichier CSV de watchlist (colonnes basket,symbol,weight), exporté par Quant Research. */
    public String watchlistPath = "";

    /** Vrai si le panier Mega-cap doit confirmer les rotations. */
    public boolean confirmMega = true;

    /** Écart minimum Refuge − Risk (en %) pour déclencher une rotation. */
    public double spreadThreshold = 0.50;

    /** Vrai pour activer l'hystérésis sur le régime actif. */
    public boolean hysteresis = false;

    /** Durée totale de la fenêtre glissante de corrélation, en minutes. */
    public int corrWindowMinutes = 20;

    /** Intervalle d'échantillonnage des variations pour la corrélation, en secondes. */
    public int corrSampleSeconds = 15;

    /** Facteur d'échelle verticale des courbes sur la Heatmap (ex. 0.25). */
    public double heatmapScale = 0.25;

    /**
     * Vrai pour ramener chaque courbe de la Heatmap à l'amplitude de l'instrument du graphique
     * (l'échelle manuelle ne sert alors qu'au début de séance, faute de données suffisantes).
     */
    public boolean heatmapAutoScale = true;

    /** Couleur de la courbe Chart (performance de l'instrument du graphique, Bottom Chart). */
    public int chartLineColor = DEFAULT_CHART_COLOR.getRGB();

    /** Couleur de la courbe Risk sur la Heatmap. */
    public int riskLineColor = DEFAULT_RISK_COLOR.getRGB();

    /** Couleur de la courbe Refuge sur la Heatmap. */
    public int refugeLineColor = DEFAULT_REFUGE_COLOR.getRGB();

    /** Couleur de la courbe Mega-cap sur la Heatmap. */
    public int megaLineColor = DEFAULT_MEGA_COLOR.getRGB();

    /** Couleur de la courbe Refuge − Risk dans le Bottom Chart. */
    public int spreadLineColor = DEFAULT_SPREAD_COLOR.getRGB();

    /** Couleur de la courbe Cross dans le Bottom Chart. */
    public int crossLineColor = DEFAULT_CROSS_COLOR.getRGB();

    /** Couleur de la courbe Within dans le Bottom Chart. */
    public int withinLineColor = DEFAULT_WITHIN_COLOR.getRGB();

    /** Couleur du régime Neutral. */
    public int neutralRegimeColor = DEFAULT_NEUTRAL_COLOR.getRGB();

    /** Couleur du régime Liquidation. */
    public int liquidationRegimeColor = DEFAULT_LIQUIDATION_COLOR.getRGB();

    /** Couleur du régime Slow long. */
    public int slowLongRegimeColor = DEFAULT_SLOW_LONG_COLOR.getRGB();

    /** Couleur du régime Rotation to refuge. */
    public int rotRefugeRegimeColor = DEFAULT_ROT_REFUGE_COLOR.getRGB();

    /** Couleur du régime Rotation to risk. */
    public int rotRiskRegimeColor = DEFAULT_ROT_RISK_COLOR.getRGB();
  }

  /** Ligne de rendements horodatée (en secondes epoch) pour la fenêtre de corrélation. */
  private static final class TimedReturnRow {
    final long epochSec;
    final double[] returns;

    TimedReturnRow(long epochSec, double[] returns) {
      this.epochSec = epochSec;
      this.returns = returns;
    }
  }

  private Api api;
  private Settings settings = new Settings();
  private double instrumentPips = 1.0;
  private String chartAlias = "Chart";

  // Indicateurs Heatmap (GraphType.PRIMARY : courbes activées, Widgets désactivés)
  private IndicatorModifiable riskHeatmapIndicator;
  private IndicatorModifiable refugeHeatmapIndicator;
  private IndicatorModifiable megaHeatmapIndicator;

  // Indicateurs Bottom Chart & Widgets en % (GraphType.BOTTOM : courbes + Widgets activés)
  private IndicatorModifiable chartIndicator;
  private IndicatorModifiable riskIndicator;
  private IndicatorModifiable refugeIndicator;
  private IndicatorModifiable megaIndicator;

  // Indicateurs Bottom Chart & Widgets (GraphType.BOTTOM : courbes + Widgets activés)
  private IndicatorModifiable regimeIndicator;
  private IndicatorModifiable spreadIndicator;
  private IndicatorModifiable crossIndicator;
  private IndicatorModifiable withinIndicator;

  // État horloge & prix de l'instrument du graphique Bookmap (en ticks, comme onTrade)
  private volatile long chartNanos;
  private long lastTimestampSec = Long.MIN_VALUE;
  private volatile boolean isRealtime;
  private volatile LocalDate currentRthDate;
  private volatile boolean inRegularSession;
  private volatile double chartOpen0930 = Double.NaN;
  private volatile boolean chartOpenLocked;
  private volatile boolean chartOpenEstimated;
  private volatile double chartLast = Double.NaN;
  private final TreeMap<Long, Double> chartMinuteCloses = new TreeMap<>();

  // Paniers de l'abonnement TWS en cours, figés à chaque remise à zéro de séance à partir des
  // réglages : Risk = slots 0..riskCount-1, Refuge = riskCount..stockCount-1, puis Mega
  private String[] activeRiskSymbols = new String[0];
  private String[] activeRefugeSymbols = new String[0];
  private String[] activeMegaSymbols = new String[0];
  private double[] activeMegaWeights = new double[0];
  private int riskCount;
  private int refugeCount;
  private int stockCount;
  private int megaCount;
  private int slotCount;

  // État des prix reçus de TWS (Risk puis Refuge dans stock*, Mega dans mega*)
  private double[] stockOpen = new double[0];
  private double[] stockLast = new double[0];
  private double[] megaOpen = new double[0];
  private double[] megaLast = new double[0];

  // Historique RTH 1m depuis 09h30 NY, par slot (Risk, Refuge, puis Mega)
  private LocalDate histSessionDate;
  private final TreeMap<Long, double[]> histMinuteCloses = new TreeMap<>();

  // Performances (%) tracées sur la Heatmap {Risk, Refuge, Mega} par horodatage (ns), historique
  // 1m et temps réel 1s, pour reprojeter les courbes sans perte quand l'échelle change
  private final TreeMap<Long, double[]> heatmapPerfPoints = new TreeMap<>();
  private final double[] autoScales = new double[HEATMAP_LINES];
  private long lastAutoScaleMinute = Long.MIN_VALUE;
  private long[] histFirstBarSec = new long[0];
  private long[] histLastBarSec = new long[0];
  private double[] histOpen0930 = new double[0];
  private int[] histBarCount = new int[0];
  private boolean[] histTickerDone = new boolean[0];
  private int histCompletedStocks;
  private volatile String lastTwsError = "None";

  // Redemande de l'historique du jour (09h31:05 NY, puis toutes les minutes si TWS n'a pas
  // encore la séance du jour), en secondes epoch ; -1 si rien n'est prévu
  private volatile long nextHistRequestSec = -1;
  private int histRetriesToday;

  // Watchlist CSV : résumé du dernier chargement pour le panneau de réglages et le snapshot
  private volatile String watchlistStatus = "No watchlist loaded (baskets from settings)";
  private volatile JLabel watchlistStatusLabel;
  private volatile Runnable basketFieldsRefresher;

  // Fenêtre glissante des rendements pour les corrélations temps réel sans délai
  private final Deque<TimedReturnRow> completedReturnRows = new ArrayDeque<>();
  private double[] stepStartPrices = new double[0];
  private long lastCorrStepEpochSec = -1;

  // État courant du régime
  private Regime currentRegime = Regime.NEUTRAL;
  private Color lastAppliedRegimeColor;
  private boolean wasRiskBelowChart;

  // Client TWS API
  private final TwsBridge twsBridge = new TwsBridge();
  private volatile String connectionStatus = "Disconnected";
  private volatile JLabel statusLabel;

  @Override
  public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
    this.api = api;
    this.instrumentPips = (info != null && info.pips > 0.0) ? info.pips : 1.0;
    this.chartAlias = (alias != null && !alias.isBlank()) ? alias : "Chart";
    Settings loaded = api.getSettings(Settings.class);
    if (loaded != null) {
      sanitizeSettings(loaded);
      this.settings = loaded;
    }
    // La watchlist CSV est la seule source des paniers : relue au démarrage ; si le fichier est
    // illisible, les paniers de son dernier chargement restent. Sans fichier, aucun panier.
    if (settings.watchlistPath.isBlank()) {
      clearBaskets(settings);
      setWatchlistStatus("No watchlist loaded: choose a CSV file with Browse…");
    } else {
      loadWatchlist(false);
    }
    resetSessionData();

    WidgetDisplayInfo symmetricZero = new WidgetDisplayInfo(WidgetDisplayInfo.Type.SYMMETRIC, 0.0);

    // 1. Courbes sur la Heatmap (GraphType.PRIMARY, sans Widgets pour éviter les prix projetés)
    riskHeatmapIndicator =
        api.registerIndicatorModifiable(
            "Risk (Heatmap)", GraphType.PRIMARY, Double.NaN, true, false);
    refugeHeatmapIndicator =
        api.registerIndicatorModifiable(
            "Refuge (Heatmap)", GraphType.PRIMARY, Double.NaN, true, false);
    megaHeatmapIndicator =
        api.registerIndicatorModifiable(
            "Mega-cap (Heatmap)", GraphType.PRIMARY, Double.NaN, true, false);

    // 2. Variations directes en % dans le Bottom Chart et les Widgets (GraphType.BOTTOM)
    chartIndicator =
        api.registerIndicatorModifiable("Chart", GraphType.BOTTOM, Double.NaN, true, true);
    chartIndicator.setWidgetRules(new WidgetRules(-2.0, 2.0, symmetricZero));

    riskIndicator =
        api.registerIndicatorModifiable("Risk", GraphType.BOTTOM, Double.NaN, true, true);
    riskIndicator.setWidgetRules(new WidgetRules(-2.0, 2.0, symmetricZero));

    refugeIndicator =
        api.registerIndicatorModifiable("Refuge", GraphType.BOTTOM, Double.NaN, true, true);
    refugeIndicator.setWidgetRules(new WidgetRules(-2.0, 2.0, symmetricZero));

    megaIndicator =
        api.registerIndicatorModifiable("Mega-cap", GraphType.BOTTOM, Double.NaN, true, true);
    megaIndicator.setWidgetRules(new WidgetRules(-2.0, 2.0, symmetricZero));

    AxisGroup perfAxisGroup = new AxisGroup();
    perfAxisGroup.add(chartIndicator);
    perfAxisGroup.add(riskIndicator);
    perfAxisGroup.add(refugeIndicator);
    perfAxisGroup.add(megaIndicator);

    // 3. Courbes et Widgets du Bottom Chart (GraphType.BOTTOM)
    regimeIndicator =
        api.registerIndicatorModifiable("Market Regime", GraphType.BOTTOM, Double.NaN, true, true);
    regimeIndicator.setWidgetRules(new WidgetRules(-2.0, 2.0, symmetricZero));

    spreadIndicator =
        api.registerIndicatorModifiable("Refuge - Risk", GraphType.BOTTOM, Double.NaN, true, true);
    spreadIndicator.setWidgetRules(new WidgetRules(-1.5, 1.5, symmetricZero));

    crossIndicator =
        api.registerIndicatorModifiable("Cross", GraphType.BOTTOM, Double.NaN, true, true);
    crossIndicator.setWidgetRules(new WidgetRules(-1.0, 1.0, symmetricZero));

    withinIndicator =
        api.registerIndicatorModifiable("Within", GraphType.BOTTOM, Double.NaN, true, true);
    withinIndicator.setWidgetRules(new WidgetRules(-1.0, 1.0, symmetricZero));

    AxisGroup corrAxisGroup = new AxisGroup();
    corrAxisGroup.add(crossIndicator);
    corrAxisGroup.add(withinIndicator);

    applyColors();
  }

  @Override
  public void stop() {
    twsBridge.shutdown();
  }

  @Override
  public void onRealtimeStart() {
    isRealtime = true;
    if (twsBridge.isSubscribed() && !histMinuteCloses.isEmpty()) {
      rebuildHistoryFrom0930();
    }
  }

  @Override
  public long getInterval() {
    return Intervals.INTERVAL_1_SECOND;
  }

  @Override
  public void onTimestamp(long nanoseconds) {
    chartNanos = nanoseconds;
    long epochSec = nanoseconds / 1_000_000_000L;
    if (epochSec == lastTimestampSec) {
      return;
    }
    lastTimestampSec = epochSec;
    ZonedDateTime ny = Instant.ofEpochSecond(epochSec).atZone(NY_ZONE);
    LocalDate nyDate = ny.toLocalDate();
    LocalTime nyTime = ny.toLocalTime();
    boolean nowRegular = !nyTime.isBefore(RTH_OPEN) && nyTime.isBefore(RTH_CLOSE);
    boolean wasRegular = inRegularSession;
    inRegularSession = nowRegular;

    // Nouvelle ouverture RTH à 09h30 America/New_York sur le graphique Bookmap
    if (nowRegular && (!wasRegular || !nyDate.equals(currentRthDate))) {
      boolean dateChanged = !nyDate.equals(currentRthDate);
      currentRthDate = nyDate;
      if (dateChanged) {
        synchronized (this) {
          chartMinuteCloses.clear();
        }
        chartOpenEstimated = false;
        if (nyTime.isBefore(LocalTime.of(9, 31)) && !Double.isNaN(chartLast)) {
          chartOpen0930 = chartLast;
          chartOpenLocked = true;
        } else {
          chartOpen0930 = Double.NaN;
          chartOpenLocked = false;
        }
      }
      if (isRealtime && twsBridge.isSubscribed() && !nyDate.equals(histSessionDate)) {
        onNewRegularSessionOpen(nyDate);
      }
    }

    // Redemande programmée de l'historique du jour (hors du verrou du module : lock TWS d'abord)
    long requestAt = nextHistRequestSec;
    if (isRealtime && requestAt > 0 && epochSec >= requestAt) {
      nextHistRequestSec = -1;
      twsBridge.resubscribeIfSubscribed();
    }
  }

  @Override
  public void onTrade(double price, int size, TradeInfo tradeInfo) {
    chartLast = price;
    long ts = chartNanos;
    if (ts > 0) {
      long minuteSec = (ts / 1_000_000_000L / 60L) * 60L;
      synchronized (this) {
        chartMinuteCloses.put(minuteSec, price);
      }
      if (inRegularSession && !chartOpenLocked) {
        ZonedDateTime ny = Instant.ofEpochSecond(0, ts).atZone(NY_ZONE);
        if (ny.toLocalTime().isBefore(LocalTime.of(9, 31))) {
          chartOpen0930 = price;
          chartOpenLocked = true;
        }
      }
    }
  }

  @Override
  public synchronized void onInterval() {
    if (!isRealtime || !twsBridge.isSubscribed()) {
      return;
    }
    if (!inRegularSession) {
      plotNaN();
      return;
    }
    if (histCompletedStocks < slotCount) {
      return;
    }

    double riskPerf = calcBasketPerf(0, riskCount);
    double refPerf = calcBasketPerf(riskCount, stockCount);
    double megaPerf = calcMegaPerf();
    ensureChartOpen0930(calcCompositeStockPerf(riskPerf, refPerf, megaPerf));

    // L'instrument du graphique Bookmap est la référence du régime de marché
    double chartPerf = calcStockPerf(chartOpen0930, chartLast);
    double basketSpread =
        (!Double.isNaN(refPerf) && !Double.isNaN(riskPerf)) ? refPerf - riskPerf : Double.NaN;

    double[] corrs = updateAndCalcRealtimeCorrelations();
    double withinCorr = corrs[0];
    double crossCorr = corrs[1];

    updateRegimeState(chartPerf, riskPerf, refPerf, megaPerf);

    // 1. Tracé continu des courbes sur la Heatmap depuis 09h30 NY
    if (chartNanos > 0) {
      long minute = chartNanos / 60_000_000_000L;
      if (settings.heatmapAutoScale && minute != lastAutoScaleMinute) {
        lastAutoScaleMinute = minute;
        updateAutoScales(false);
      }
      heatmapPerfPoints.put(chartNanos, new double[] {riskPerf, refPerf, megaPerf, chartPerf});
    }
    riskHeatmapIndicator.addPoint(projectFromOpen0930(0, riskPerf));
    refugeHeatmapIndicator.addPoint(projectFromOpen0930(1, refPerf));
    megaHeatmapIndicator.addPoint(projectFromOpen0930(2, megaPerf));

    // 2. Tracé des variations directes en % depuis 09h30 NY (Bottom Chart + Widgets)
    chartIndicator.addPoint(chartPerf);
    riskIndicator.addPoint(riskPerf);
    refugeIndicator.addPoint(refPerf);
    megaIndicator.addPoint(megaPerf);

    // 3. Tracé du régime, du spread et des corrélations dans le Bottom Chart
    Color targetRegimeColor = colorForRegime(currentRegime);
    if (!targetRegimeColor.equals(lastAppliedRegimeColor)) {
      regimeIndicator.setColor(targetRegimeColor);
      lastAppliedRegimeColor = targetRegimeColor;
    }
    regimeIndicator.addPoint(currentRegime.score);
    spreadIndicator.addPoint(basketSpread);
    crossIndicator.addPoint(crossCorr);
    withinIndicator.addPoint(withinCorr);
  }

  /**
   * Dernier recours si Bookmap n'a aucun trade de l'instrument à 09h30 NY (graphique ouvert en
   * cours de séance sans historique) : estime son ouverture à partir du composite des paniers.
   */
  private void ensureChartOpen0930(double fallbackPerf) {
    if (!chartOpenLocked && !Double.isNaN(chartLast) && !Double.isNaN(fallbackPerf)) {
      chartOpen0930 = chartLast / (1.0 + fallbackPerf / 100.0);
      chartOpenLocked = true;
      chartOpenEstimated = true;
    }
  }

  private LocalDate resolveChartRthDate() {
    if (isRealtime && currentRthDate != null) {
      return currentRthDate;
    }
    if (!chartMinuteCloses.isEmpty()) {
      return Instant.ofEpochSecond(chartMinuteCloses.lastKey()).atZone(NY_ZONE).toLocalDate();
    }
    if (histSessionDate != null) {
      return histSessionDate;
    }
    if (currentRthDate != null) {
      return currentRthDate;
    }
    return ZonedDateTime.now(NY_ZONE).toLocalDate();
  }

  private double projectFromOpen0930(int line, double perfPercent) {
    if (Double.isNaN(perfPercent)) {
      return Double.NaN;
    }
    double baseLevel =
        (!Double.isNaN(chartOpen0930) && chartOpen0930 > 0.0) ? chartOpen0930 : chartLast;
    if (Double.isNaN(baseLevel) || baseLevel <= 0.0) {
      return Double.NaN;
    }
    return baseLevel * (1.0 + (perfPercent * heatmapScaleFor(line)) / 100.0);
  }

  private double heatmapScaleFor(int line) {
    double auto = autoScales[line];
    return (settings.heatmapAutoScale && !Double.isNaN(auto)) ? auto : settings.heatmapScale;
  }

  /**
   * Mode Auto : l'échelle de chaque courbe de la Heatmap est le rapport entre l'écart-type des
   * variations 1m de l'instrument du graphique et celui du panier depuis 09h30 NY, pour que chaque
   * courbe ait l'amplitude du prix. Les courbes ne sont retracées que si une échelle bouge de plus
   * de 5 % (ou si {@code forceRedraw}), afin de garder un tracé cohérent sans retracer chaque minute.
   */
  private synchronized void updateAutoScales(boolean forceRedraw) {
    double[] next = new double[HEATMAP_LINES];
    Arrays.fill(next, Double.NaN);
    if (settings.heatmapAutoScale) {
      double[] sumSqChart = new double[HEATMAP_LINES];
      double[] sumSqLine = new double[HEATMAP_LINES];
      int[] steps = new int[HEATMAP_LINES];
      double[] prevRow = null;
      double[] minuteRow = null;
      long currentMinute = Long.MIN_VALUE;
      // Échantillonne la dernière valeur de chaque minute, puis compare les minutes successives
      for (Map.Entry<Long, double[]> entry : heatmapPerfPoints.entrySet()) {
        long minute = entry.getKey() / 60_000_000_000L;
        if (minute != currentMinute && minuteRow != null) {
          if (prevRow != null) {
            accumulateAutoScaleStep(prevRow, minuteRow, sumSqChart, sumSqLine, steps);
          }
          prevRow = minuteRow;
        }
        currentMinute = minute;
        minuteRow = entry.getValue();
      }
      if (prevRow != null && minuteRow != null) {
        accumulateAutoScaleStep(prevRow, minuteRow, sumSqChart, sumSqLine, steps);
      }
      for (int i = 0; i < HEATMAP_LINES; i++) {
        if (steps[i] >= MIN_AUTO_SCALE_STEPS && sumSqChart[i] > 0.0 && sumSqLine[i] > 0.0) {
          double ratio = Math.sqrt(sumSqChart[i] / sumSqLine[i]);
          next[i] = Math.max(MIN_HEATMAP_SCALE, Math.min(MAX_HEATMAP_SCALE, ratio));
        }
      }
    }

    boolean changed = forceRedraw;
    for (int i = 0; i < HEATMAP_LINES; i++) {
      double old = autoScales[i];
      if (Double.isNaN(old) != Double.isNaN(next[i])
          || (!Double.isNaN(old) && Math.abs(next[i] / old - 1.0) > AUTO_SCALE_REDRAW_RATIO)) {
        changed = true;
      }
    }
    if (changed) {
      System.arraycopy(next, 0, autoScales, 0, HEATMAP_LINES);
      reprojectHeatmapLines();
    }
  }

  private static void accumulateAutoScaleStep(
      double[] prevRow, double[] row, double[] sumSqChart, double[] sumSqLine, int[] steps) {
    double chartStep = row[PERF_CHART] - prevRow[PERF_CHART];
    if (Double.isNaN(chartStep)) {
      return;
    }
    for (int i = 0; i < HEATMAP_LINES; i++) {
      double lineStep = row[i] - prevRow[i];
      if (!Double.isNaN(lineStep)) {
        sumSqChart[i] += chartStep * chartStep;
        sumSqLine[i] += lineStep * lineStep;
        steps[i]++;
      }
    }
  }

  private void plotNaN() {
    if (riskIndicator == null) {
      return;
    }
    riskHeatmapIndicator.addPoint(Double.NaN);
    refugeHeatmapIndicator.addPoint(Double.NaN);
    megaHeatmapIndicator.addPoint(Double.NaN);
    chartIndicator.addPoint(Double.NaN);
    riskIndicator.addPoint(Double.NaN);
    refugeIndicator.addPoint(Double.NaN);
    megaIndicator.addPoint(Double.NaN);
    regimeIndicator.addPoint(Double.NaN);
    spreadIndicator.addPoint(Double.NaN);
    crossIndicator.addPoint(Double.NaN);
    withinIndicator.addPoint(Double.NaN);
  }

  // ======================================================================
  //  Calculs des paniers, des corrélations temps réel et du régime
  // ======================================================================

  private static double calcStockPerf(double open, double last) {
    if (Double.isNaN(open) || Double.isNaN(last) || open <= 0.0 || last <= 0.0) {
      return Double.NaN;
    }
    return (last / open - 1.0) * 100.0;
  }

  private static double calcCompositeStockPerf(double riskPerf, double refPerf, double megaPerf) {
    double weightedSum = 0.0;
    double weightTotal = 0.0;
    if (!Double.isNaN(megaPerf)) {
      weightedSum += 0.60 * megaPerf;
      weightTotal += 0.60;
    }
    if (!Double.isNaN(riskPerf)) {
      weightedSum += 0.25 * riskPerf;
      weightTotal += 0.25;
    }
    if (!Double.isNaN(refPerf)) {
      weightedSum += 0.15 * refPerf;
      weightTotal += 0.15;
    }
    return weightTotal > 0.0 ? weightedSum / weightTotal : Double.NaN;
  }

  private double calcBasketPerf(int startInclusive, int endExclusive) {
    return calcBasketPerfFromArray(stockLast, startInclusive, endExclusive);
  }

  private double calcBasketPerfFromArray(
      double[] currentPrices, int startInclusive, int endExclusive) {
    double total = 0.0;
    int count = 0;
    for (int i = startInclusive; i < endExclusive; i++) {
      double perf = calcStockPerf(stockOpen[i], currentPrices[i]);
      if (!Double.isNaN(perf)) {
        total += perf;
        count++;
      }
    }
    return count >= MIN_BASKET_STOCKS ? total / count : Double.NaN;
  }

  private double calcMegaPerf() {
    return calcMegaPerfFromArray(megaLast);
  }

  private double calcMegaPerfFromArray(double[] currentMegaPrices) {
    double total = 0.0;
    double weightSum = 0.0;
    int count = 0;
    for (int i = 0; i < megaCount; i++) {
      double perf = calcStockPerf(megaOpen[i], currentMegaPrices[i]);
      double weight = activeMegaWeights[i];
      if (!Double.isNaN(perf) && weight > 0.0) {
        total += perf * weight;
        weightSum += weight;
        count++;
      }
    }
    return (count >= MIN_MEGA_STOCKS && weightSum > 0.0) ? total / weightSum : Double.NaN;
  }

  private synchronized double[] updateAndCalcRealtimeCorrelations() {
    long nowSec = chartNanos > 0 ? chartNanos / 1_000_000_000L : System.currentTimeMillis() / 1000L;
    int stepSec = Math.max(5, settings.corrSampleSeconds);
    int totalWindowSec = Math.max(60, settings.corrWindowMinutes * 60);

    for (int j = 0; j < stockCount; j++) {
      if (Double.isNaN(stepStartPrices[j]) && !Double.isNaN(stockLast[j]) && stockLast[j] > 0.0) {
        stepStartPrices[j] = stockLast[j];
      }
    }
    if (lastCorrStepEpochSec < 0) {
      lastCorrStepEpochSec = nowSec;
      return calcCorrelationsFromWindow(null);
    }

    long elapsedSec = Math.max(1L, nowSec - lastCorrStepEpochSec);
    double[] currentRow = buildNormalizedReturnRow(stepStartPrices, stockLast, elapsedSec);
    boolean validRow = isWindowRowValid(currentRow);

    if (nowSec - lastCorrStepEpochSec >= stepSec) {
      if (validRow) {
        completedReturnRows.addLast(new TimedReturnRow(nowSec, currentRow.clone()));
      }
      for (int j = 0; j < stockCount; j++) {
        if (!Double.isNaN(stockLast[j]) && stockLast[j] > 0.0) {
          stepStartPrices[j] = stockLast[j];
        }
      }
      lastCorrStepEpochSec = nowSec;
      validRow = false;
    }
    while (!completedReturnRows.isEmpty()
        && nowSec - completedReturnRows.peekFirst().epochSec > totalWindowSec) {
      completedReturnRows.removeFirst();
    }

    return calcCorrelationsFromWindow(validRow ? currentRow : null);
  }

  private double[] calcCorrelationsFromWindow(double[] extraCurrentRow) {
    int totalRows = completedReturnRows.size() + (extraCurrentRow != null ? 1 : 0);
    if (totalRows < MIN_CORR_STEPS) {
      return new double[] {Double.NaN, Double.NaN};
    }
    double[][] matrix = new double[totalRows][stockCount];
    int r = 0;
    for (TimedReturnRow timedRow : completedReturnRows) {
      matrix[r++] = timedRow.returns;
    }
    if (extraCurrentRow != null) {
      matrix[r] = extraCurrentRow;
    }
    return calcBasketCorrelations(matrix, riskCount);
  }

  private double[] buildNormalizedReturnRow(
      double[] prevPrices, double[] currPrices, long dtSec) {
    double scale = 1.0 / Math.sqrt(Math.max(1L, dtSec));
    double[] row = new double[stockCount];
    for (int j = 0; j < stockCount; j++) {
      double prev = prevPrices[j];
      double curr = currPrices[j];
      if (!Double.isNaN(prev) && prev > 0.0 && !Double.isNaN(curr) && curr > 0.0) {
        row[j] = (curr / prev - 1.0) * scale;
      } else {
        row[j] = Double.NaN;
      }
    }
    return row;
  }

  private boolean isWindowRowValid(double[] row) {
    int validRisk = 0;
    int validRef = 0;
    for (int j = 0; j < riskCount; j++) {
      if (!Double.isNaN(row[j])) {
        validRisk++;
      }
    }
    for (int j = riskCount; j < stockCount; j++) {
      if (!Double.isNaN(row[j])) {
        validRef++;
      }
    }
    return validRisk >= MIN_BASKET_STOCKS && validRef >= MIN_BASKET_STOCKS;
  }

  /**
   * Calcule les corrélations moyennes intra-paniers (Within) et inter-paniers (Cross) sans boucler
   * sur les paires, comme {@code calcBasketCorrelations} dans {@code market regime.pine}.
   */
  private double[] calcBasketCorrelations(double[][] retMatrix, int splitIndex) {
    int rowCount = retMatrix.length;
    if (rowCount < MIN_CORR_STEPS) {
      return new double[] {Double.NaN, Double.NaN};
    }
    double[] zSumsA = new double[rowCount];
    double[] zSumsB = new double[rowCount];
    int countA = 0;
    int countB = 0;

    for (int j = 0; j < stockCount; j++) {
      double[] col = getActiveColumn(retMatrix, j);
      if (col == null) {
        continue;
      }
      double stdev = stdevPopulation(col);
      if (stdev > 0.0) {
        boolean inGroupA = j < splitIndex;
        double[] zSums = inGroupA ? zSumsA : zSumsB;
        if (inGroupA) {
          countA++;
        } else {
          countB++;
        }
        for (int i = 0; i < rowCount; i++) {
          zSums[i] += col[i] / stdev;
        }
      }
    }
    if (countA < MIN_BASKET_STOCKS || countB < MIN_BASKET_STOCKS) {
      return new double[] {Double.NaN, Double.NaN};
    }
    double withinPairs = (countA * (countA - 1) + countB * (countB - 1)) / 2.0;
    double withinResult =
        (variancePopulation(zSumsA) - countA + variancePopulation(zSumsB) - countB)
            / (2.0 * withinPairs);
    double crossResult = covariancePopulation(zSumsA, zSumsB) / (countA * countB);
    return new double[] {withinResult, crossResult};
  }

  private static double[] getActiveColumn(double[][] retMatrix, int colIndex) {
    int rowCount = retMatrix.length;
    double[] col = new double[rowCount];
    int valid = 0;
    for (int i = 0; i < rowCount; i++) {
      double v = retMatrix[i][colIndex];
      if (!Double.isNaN(v)) {
        valid++;
        col[i] = v;
      } else {
        col[i] = 0.0;
      }
    }
    return valid >= rowCount * MIN_ACTIVE_SHARE ? col : null;
  }

  private static double variancePopulation(double[] values) {
    int n = values.length;
    if (n == 0) {
      return 0.0;
    }
    double mean = 0.0;
    for (double v : values) {
      mean += v;
    }
    mean /= n;
    double sumSq = 0.0;
    for (double v : values) {
      double d = v - mean;
      sumSq += d * d;
    }
    return sumSq / n;
  }

  private static double stdevPopulation(double[] values) {
    return Math.sqrt(variancePopulation(values));
  }

  private static double covariancePopulation(double[] a, double[] b) {
    int n = a.length;
    if (n == 0) {
      return 0.0;
    }
    double meanA = 0.0;
    double meanB = 0.0;
    for (int i = 0; i < n; i++) {
      meanA += a[i];
      meanB += b[i];
    }
    meanA /= n;
    meanB /= n;
    double sum = 0.0;
    for (int i = 0; i < n; i++) {
      sum += (a[i] - meanA) * (b[i] - meanB);
    }
    return sum / n;
  }

  private void updateRegimeState(
      double chartPerf, double riskPerf, double refPerf, double megaPerf) {
    boolean isMeasured =
        !Double.isNaN(chartPerf) && !Double.isNaN(riskPerf) && !Double.isNaN(refPerf);
    if (!isMeasured) {
      currentRegime = Regime.NEUTRAL;
      return;
    }
    if (riskPerf < chartPerf) {
      wasRiskBelowChart = true;
    }
    if (!checkRegime(
        currentRegime, currentRegime, chartPerf, riskPerf, refPerf, megaPerf, wasRiskBelowChart)) {
      if (checkRegime(
          Regime.ROTATION_REFUGE,
          currentRegime,
          chartPerf,
          riskPerf,
          refPerf,
          megaPerf,
          wasRiskBelowChart)) {
        currentRegime = Regime.ROTATION_REFUGE;
      } else if (checkRegime(
          Regime.LIQUIDATION,
          currentRegime,
          chartPerf,
          riskPerf,
          refPerf,
          megaPerf,
          wasRiskBelowChart)) {
        currentRegime = Regime.LIQUIDATION;
      } else if (checkRegime(
          Regime.ROTATION_RISK,
          currentRegime,
          chartPerf,
          riskPerf,
          refPerf,
          megaPerf,
          wasRiskBelowChart)) {
        currentRegime = Regime.ROTATION_RISK;
      } else if (checkRegime(
          Regime.SLOW_LONG,
          currentRegime,
          chartPerf,
          riskPerf,
          refPerf,
          megaPerf,
          wasRiskBelowChart)) {
        currentRegime = Regime.SLOW_LONG;
      } else {
        currentRegime = Regime.NEUTRAL;
      }
    }
  }

  private boolean checkRegime(
      Regime target,
      Regime current,
      double chartPerf,
      double riskPerf,
      double refPerf,
      double megaPerf,
      boolean wasBelowChart) {
    if (target == Regime.NEUTRAL) {
      return false;
    }
    boolean isActive = (target == current) && settings.hysteresis;
    double signLevel = isActive ? PERF_HYSTERESIS : 0.0;
    double spreadLevel = settings.spreadThreshold * (isActive ? SPREAD_EXIT_RATIO : 1.0);
    double spread = refPerf - riskPerf;

    boolean isRiskDown = riskPerf < signLevel;
    boolean isRiskUp = riskPerf > -signLevel;
    boolean isChartDown = chartPerf < signLevel && isRiskDown;
    boolean isRiskCrossLong = wasBelowChart && riskPerf > chartPerf - signLevel;
    boolean isMegaTightLong =
        !Double.isNaN(megaPerf)
            && chartPerf > -signLevel
            && megaPerf > chartPerf - signLevel
            && (megaPerf - chartPerf) <= MAX_MEGA_CHART_GAP;
    boolean isSlowLong = isRiskCrossLong || isMegaTightLong;

    boolean isMegaDown =
        !settings.confirmMega || (!Double.isNaN(megaPerf) && megaPerf < signLevel);
    boolean isMegaUp = !settings.confirmMega || (!Double.isNaN(megaPerf) && megaPerf > -signLevel);

    return switch (target) {
      case ROTATION_REFUGE ->
          isChartDown && isMegaDown && refPerf > -signLevel && spread >= spreadLevel;
      case LIQUIDATION -> isChartDown && refPerf < signLevel;
      case ROTATION_RISK ->
          isRiskUp && isMegaUp && refPerf < signLevel && -spread >= spreadLevel;
      case SLOW_LONG -> isSlowLong;
      case NEUTRAL -> false;
    };
  }

  /**
   * Remet la séance à zéro et fige les paniers des réglages pour le prochain abonnement TWS : les
   * tableaux de prix et d'historique sont redimensionnés à la taille de chaque panier.
   */
  private synchronized void resetSessionData() {
    activeRiskSymbols = settings.riskSymbols.clone();
    activeRefugeSymbols = settings.refugeSymbols.clone();
    activeMegaSymbols = settings.megaSymbols.clone();
    activeMegaWeights = settings.megaWeights.clone();
    riskCount = activeRiskSymbols.length;
    refugeCount = activeRefugeSymbols.length;
    stockCount = riskCount + refugeCount;
    megaCount = activeMegaSymbols.length;
    slotCount = stockCount + megaCount;

    stockOpen = nanArray(stockCount);
    stockLast = nanArray(stockCount);
    megaOpen = nanArray(megaCount);
    megaLast = nanArray(megaCount);
    stepStartPrices = nanArray(stockCount);
    histSessionDate = null;
    histMinuteCloses.clear();
    histFirstBarSec = new long[slotCount];
    histLastBarSec = new long[slotCount];
    Arrays.fill(histFirstBarSec, Long.MAX_VALUE);
    Arrays.fill(histLastBarSec, Long.MIN_VALUE);
    histOpen0930 = nanArray(slotCount);
    histBarCount = new int[slotCount];
    histTickerDone = new boolean[slotCount];
    histCompletedStocks = 0;
    lastTwsError = "None";
    heatmapPerfPoints.clear();
    Arrays.fill(autoScales, Double.NaN);
    lastAutoScaleMinute = Long.MIN_VALUE;
    completedReturnRows.clear();
    lastCorrStepEpochSec = -1;
    wasRiskBelowChart = false;
    currentRegime = Regime.NEUTRAL;
  }

  private static double[] nanArray(int size) {
    double[] arr = new double[size];
    Arrays.fill(arr, Double.NaN);
    return arr;
  }

  /**
   * Ouverture RTH en temps réel alors que l'abonnement TWS date d'avant 09h30 : à 09h30:00, TWS
   * renvoie encore l'ouverture de la veille (tick Open) et un historique sans barre du jour. La
   * séance est donc remise à zéro tout de suite, mais l'historique n'est redemandé qu'à 09h31:05,
   * une fois la première barre 1m de la séance close.
   */
  private void onNewRegularSessionOpen(LocalDate sessionDate) {
    resetSessionData();
    synchronized (this) {
      histRetriesToday = 0;
    }
    nextHistRequestSec =
        sessionDate.atTime(HIST_REQUEST_AFTER_OPEN).atZone(NY_ZONE).toInstant().getEpochSecond();
  }

  /**
   * Rejoue toutes les barres historiques de 1 minute depuis 09h30 NY pour reconstruire l'état du
   * régime, la mémoire {@code wasRiskBelowChart}, la fenêtre glissante de corrélation de 20 minutes
   * et tracer tout l'historique des courbes sur Bookmap.
   */
  private synchronized void rebuildHistoryFrom0930() {
    if (riskIndicator == null || histSessionDate == null || histMinuteCloses.isEmpty()) {
      return;
    }

    // 1. Garantit que les prix d'ouverture 09h30 NY proviennent de la barre de 09h30 NY
    for (int i = 0; i < stockCount; i++) {
      double hOpen = histOpen0930[i];
      if (!Double.isNaN(hOpen) && hOpen > 0.0) {
        stockOpen[i] = hOpen;
      }
    }
    for (int i = 0; i < megaCount; i++) {
      double hOpen = histOpen0930[stockCount + i];
      if (!Double.isNaN(hOpen) && hOpen > 0.0) {
        megaOpen[i] = hOpen;
      }
    }

    long histRthStartSec =
        histSessionDate.atTime(RTH_OPEN).atZone(NY_ZONE).toInstant().getEpochSecond();
    LocalDate targetChartDate = resolveChartRthDate();
    long chartRthStartSec =
        targetChartDate.atTime(RTH_OPEN).atZone(NY_ZONE).toInstant().getEpochSecond();
    long chartRthStartNanos = chartRthStartSec * 1_000_000_000L;

    if (!chartOpenLocked) {
      Map.Entry<Long, Double> firstChart = chartMinuteCloses.ceilingEntry(chartRthStartSec);
      if (firstChart != null && firstChart.getKey() < chartRthStartSec + 60L) {
        chartOpen0930 = firstChart.getValue();
        chartOpenLocked = true;
        chartOpenEstimated = false;
      } else {
        ensureChartOpen0930(
            calcCompositeStockPerf(
                calcBasketPerf(0, riskCount),
                calcBasketPerf(riskCount, stockCount),
                calcMegaPerf()));
      }
    }

    // 2. Vide intégralement les indicateurs avant d'insérer par ordre chronologique strict
    clearIndicators(0L, Long.MAX_VALUE / 2);
    heatmapPerfPoints.clear();
    Arrays.fill(autoScales, Double.NaN);

    // 3. Réinitialise l'état de séance et rejoue chaque minute depuis 09h30 NY
    wasRiskBelowChart = false;
    currentRegime = Regime.NEUTRAL;
    completedReturnRows.clear();

    double[] runningStock = stockOpen.clone();
    double[] prevStepStock = stockOpen.clone();
    double[] runningMega = megaOpen.clone();
    int totalWindowSec = Math.max(60, settings.corrWindowMinutes * 60);

    // Point d'origine à 09h30:00 NY (0.0 %)
    addHistoricalPointAt(
        chartRthStartNanos,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        Double.NaN,
        Double.NaN,
        Regime.NEUTRAL);

    long lastBarEndSec = chartRthStartSec;
    for (Map.Entry<Long, double[]> entry : histMinuteCloses.entrySet()) {
      long minSec = entry.getKey();
      if (minSec < histRthStartSec) {
        continue;
      }
      long offsetSec = minSec - histRthStartSec;
      long barEndSec = chartRthStartSec + offsetSec + 60L;
      long barEndNanos = barEndSec * 1_000_000_000L;
      double[] closes = entry.getValue();

      for (int i = 0; i < stockCount; i++) {
        double c = closes[i];
        if (!Double.isNaN(c) && c > 0.0) {
          runningStock[i] = c;
        }
      }
      for (int i = 0; i < megaCount; i++) {
        double c = closes[stockCount + i];
        if (!Double.isNaN(c) && c > 0.0) {
          runningMega[i] = c;
        }
      }

      double hRiskPerf = calcBasketPerfFromArray(runningStock, 0, riskCount);
      double hRefPerf = calcBasketPerfFromArray(runningStock, riskCount, stockCount);
      double hMegaPerf = calcMegaPerfFromArray(runningMega);

      // Dernier prix Bookmap de l'instrument du graphique à la clôture de cette minute
      Map.Entry<Long, Double> chartEntry = chartMinuteCloses.floorEntry(barEndSec - 60L);
      double hChartPerf =
          (chartEntry != null && chartEntry.getKey() >= chartRthStartSec)
              ? calcStockPerf(chartOpen0930, chartEntry.getValue())
              : Double.NaN;
      double hSpread =
          (!Double.isNaN(hRefPerf) && !Double.isNaN(hRiskPerf)) ? hRefPerf - hRiskPerf : Double.NaN;

      double[] retRow = buildNormalizedReturnRow(prevStepStock, runningStock, 60L);
      for (int j = 0; j < stockCount; j++) {
        if (!Double.isNaN(runningStock[j]) && runningStock[j] > 0.0) {
          prevStepStock[j] = runningStock[j];
        }
      }
      if (isWindowRowValid(retRow)) {
        completedReturnRows.addLast(new TimedReturnRow(barEndSec, retRow));
      }
      while (!completedReturnRows.isEmpty()
          && barEndSec - completedReturnRows.peekFirst().epochSec > totalWindowSec) {
        completedReturnRows.removeFirst();
      }

      double[] hCorrs = calcCorrelationsFromWindow(null);
      updateRegimeState(hChartPerf, hRiskPerf, hRefPerf, hMegaPerf);

      addHistoricalPointAt(
          barEndNanos,
          hChartPerf,
          hRiskPerf,
          hRefPerf,
          hMegaPerf,
          hSpread,
          hCorrs[1],
          hCorrs[0],
          currentRegime);
      lastBarEndSec = barEndSec;
    }

    // 4. Prépare la continuité temps réel à la fin de l'historique
    for (int j = 0; j < stockCount; j++) {
      stepStartPrices[j] =
          (!Double.isNaN(prevStepStock[j]) && prevStepStock[j] > 0.0)
              ? prevStepStock[j]
              : stockLast[j];
    }
    lastCorrStepEpochSec = lastBarEndSec;
    updateAutoScales(false);

    Color targetRegimeColor = colorForRegime(currentRegime);
    if (!targetRegimeColor.equals(lastAppliedRegimeColor)) {
      regimeIndicator.setColor(targetRegimeColor);
      lastAppliedRegimeColor = targetRegimeColor;
    }
  }

  private void clearIndicators(long fromNanos, long toNanos) {
    if (toNanos <= fromNanos) {
      return;
    }
    riskHeatmapIndicator.clear(fromNanos, toNanos);
    refugeHeatmapIndicator.clear(fromNanos, toNanos);
    megaHeatmapIndicator.clear(fromNanos, toNanos);
    chartIndicator.clear(fromNanos, toNanos);
    riskIndicator.clear(fromNanos, toNanos);
    refugeIndicator.clear(fromNanos, toNanos);
    megaIndicator.clear(fromNanos, toNanos);
    regimeIndicator.clear(fromNanos, toNanos);
    spreadIndicator.clear(fromNanos, toNanos);
    crossIndicator.clear(fromNanos, toNanos);
    withinIndicator.clear(fromNanos, toNanos);
  }

  private void addHistoricalPointAt(
      long timestampNanos,
      double chartPerf,
      double riskPerf,
      double refPerf,
      double megaPerf,
      double spread,
      double crossCorr,
      double withinCorr,
      Regime regime) {
    heatmapPerfPoints.put(timestampNanos, new double[] {riskPerf, refPerf, megaPerf, chartPerf});
    addPointIfValid(riskHeatmapIndicator, timestampNanos, projectFromOpen0930(0, riskPerf));
    addPointIfValid(refugeHeatmapIndicator, timestampNanos, projectFromOpen0930(1, refPerf));
    addPointIfValid(megaHeatmapIndicator, timestampNanos, projectFromOpen0930(2, megaPerf));

    addPointIfValid(chartIndicator, timestampNanos, chartPerf);
    addPointIfValid(riskIndicator, timestampNanos, riskPerf);
    addPointIfValid(refugeIndicator, timestampNanos, refPerf);
    addPointIfValid(megaIndicator, timestampNanos, megaPerf);

    regimeIndicator.addPoint(timestampNanos, regime.score);
    addPointIfValid(spreadIndicator, timestampNanos, spread);
    addPointIfValid(crossIndicator, timestampNanos, crossCorr);
    addPointIfValid(withinIndicator, timestampNanos, withinCorr);
  }

  /**
   * Retrace uniquement les 3 courbes de la Heatmap avec l'échelle courante, à partir de tous les
   * points déjà tracés (historique 1m et temps réel 1s), sans rejouer l'historique TWS.
   */
  private synchronized void reprojectHeatmapLines() {
    if (riskHeatmapIndicator == null || heatmapPerfPoints.isEmpty()) {
      return;
    }
    riskHeatmapIndicator.clear(0L, Long.MAX_VALUE / 2);
    refugeHeatmapIndicator.clear(0L, Long.MAX_VALUE / 2);
    megaHeatmapIndicator.clear(0L, Long.MAX_VALUE / 2);
    for (Map.Entry<Long, double[]> entry : heatmapPerfPoints.entrySet()) {
      long ts = entry.getKey();
      double[] perfs = entry.getValue();
      addPointIfValid(riskHeatmapIndicator, ts, projectFromOpen0930(0, perfs[0]));
      addPointIfValid(refugeHeatmapIndicator, ts, projectFromOpen0930(1, perfs[1]));
      addPointIfValid(megaHeatmapIndicator, ts, projectFromOpen0930(2, perfs[2]));
    }
  }

  private static void addPointIfValid(
      IndicatorModifiable indicator, long timestampNanos, double value) {
    if (!Double.isNaN(value)) {
      indicator.addPoint(timestampNanos, value);
    }
  }

  private int slotForTickerId(int tickerId) {
    if (tickerId >= TICKER_RISK_BASE && tickerId < TICKER_RISK_BASE + riskCount) {
      return tickerId - TICKER_RISK_BASE;
    }
    if (tickerId >= TICKER_REF_BASE && tickerId < TICKER_REF_BASE + refugeCount) {
      return riskCount + (tickerId - TICKER_REF_BASE);
    }
    if (tickerId >= TICKER_MEGA_BASE && tickerId < TICKER_MEGA_BASE + megaCount) {
      return stockCount + (tickerId - TICKER_MEGA_BASE);
    }
    return -1;
  }

  private double[] emptySlotArray() {
    double[] arr = new double[slotCount];
    Arrays.fill(arr, Double.NaN);
    return arr;
  }

  private static long parseBarEpochSeconds(String raw) {
    if (raw == null) {
      return -1L;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return -1L;
    }
    boolean allDigits = true;
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        allDigits = false;
        break;
      }
    }
    if (allDigits && s.length() != 8) {
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        return -1L;
      }
    }
    try {
      String[] parts = s.split("\\s+");
      if (parts.length >= 2 && parts[0].length() == 8) {
        LocalDate d = LocalDate.parse(parts[0], DateTimeFormatter.BASIC_ISO_DATE);
        LocalTime t = LocalTime.parse(parts[1]);
        ZoneId z = NY_ZONE;
        if (parts.length >= 3) {
          try {
            z = ZoneId.of(parts[2], ZoneId.SHORT_IDS);
          } catch (Exception ignored) {
            z = NY_ZONE;
          }
        }
        return ZonedDateTime.of(d, t, z).toInstant().getEpochSecond();
      }
    } catch (Exception ignored) {
      // Format inattendu
    }
    return -1L;
  }

  private Color colorForRegime(Regime regime) {
    return switch (regime) {
      case LIQUIDATION -> new Color(settings.liquidationRegimeColor);
      case SLOW_LONG -> new Color(settings.slowLongRegimeColor);
      case ROTATION_REFUGE -> new Color(settings.rotRefugeRegimeColor);
      case ROTATION_RISK -> new Color(settings.rotRiskRegimeColor);
      case NEUTRAL -> new Color(settings.neutralRegimeColor);
    };
  }

  private void applyColors() {
    if (riskIndicator == null) {
      return;
    }
    Color chartCol = new Color(settings.chartLineColor);
    Color riskCol = new Color(settings.riskLineColor);
    Color refCol = new Color(settings.refugeLineColor);
    Color megaCol = new Color(settings.megaLineColor);

    riskHeatmapIndicator.setColor(riskCol);
    refugeHeatmapIndicator.setColor(refCol);
    megaHeatmapIndicator.setColor(megaCol);

    chartIndicator.setColor(chartCol);
    riskIndicator.setColor(riskCol);
    refugeIndicator.setColor(refCol);
    megaIndicator.setColor(megaCol);

    spreadIndicator.setColor(new Color(settings.spreadLineColor));
    crossIndicator.setColor(new Color(settings.crossLineColor));
    withinIndicator.setColor(new Color(settings.withinLineColor));

    Color regColor = colorForRegime(currentRegime);
    regimeIndicator.setColor(regColor);
    lastAppliedRegimeColor = regColor;
  }

  // ======================================================================
  //  Pont TWS API (EClientSocket + EReader + Streaming + Backfill RTH)
  // ======================================================================

  private final class TwsBridge extends DefaultEWrapper {
    private EReaderSignal signal;
    private EClientSocket client;
    private volatile boolean active;
    private volatile boolean subscribed;
    private volatile int subGeneration;
    private int[] subscribedTickerIds = new int[0];
    private boolean[] subscribedHistPending = new boolean[0];
    private int subscribedHistBase = HIST_REQ_OFFSET;
    private volatile int histIdBase = HIST_REQ_OFFSET;

    // File unique et espacée de toutes les requêtes envoyées à TWS (pacing IB)
    private final ExecutorService requestQueue =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "MarketRegime-TWS-Requests");
              t.setDaemon(true);
              return t;
            });

    boolean isSubscribed() {
      return subscribed;
    }

    synchronized void connect() {
      disconnect();
      active = true;
      subscribed = false;
      signal = new EJavaSignal();
      client = new EClientSocket(this, signal);
      String host = settings.twsHost == null ? "127.0.0.1" : settings.twsHost.trim();
      int port = settings.twsPort;
      int clientId = settings.twsClientId;

      updateStatus("Connecting to " + host + ":" + port + " (id " + clientId + ")...");
      Thread connectThread =
          new Thread(
              () -> {
                try {
                  client.eConnect(host, port, clientId);
                  if (!client.isConnected()) {
                    updateStatus("Connection failed (" + host + ":" + port + ")");
                    return;
                  }
                  final EReader reader = new EReader(client, signal);
                  reader.start();
                  while (active && client.isConnected()) {
                    signal.waitForSignal();
                    try {
                      reader.processMsgs();
                    } catch (Exception e) {
                      Log.warn("TWS EReader message error", e);
                    }
                  }
                } catch (Exception e) {
                  updateStatus("Error: " + e.getMessage());
                }
              },
              "MarketRegime-TWS-Reader");
      connectThread.setDaemon(true);
      connectThread.start();
    }

    synchronized void disconnect() {
      active = false;
      subGeneration++;
      if (client != null) {
        try {
          if (client.isConnected()) {
            // La déconnexion coupe tous les flux : inutile d'envoyer les annulations une à une
            client.eDisconnect();
          }
        } catch (Exception ignored) {
          // Fermeture silencieuse
        }
      }
      subscribed = false;
      subscribedTickerIds = new int[0];
      subscribedHistPending = new boolean[0];
      if (signal != null) {
        signal.issueSignal();
      }
      updateStatus("Disconnected");
    }

    void shutdown() {
      disconnect();
      requestQueue.shutdownNow();
    }

    /**
     * Envoie une requête à TWS depuis une file unique, espacée de {@link #REQUEST_SPACING_MS}
     * pour rester sous la limite IB de (lignes de données / 2) requêtes par seconde. Avec une
     * génération {@code gen >= 0}, la requête est abandonnée si l'abonnement a changé entre-temps.
     */
    private void enqueue(EClientSocket sock, int gen, Consumer<EClientSocket> request) {
      requestQueue.execute(
          () -> {
            if (!sock.isConnected() || (gen >= 0 && (!active || !subscribed || subGeneration != gen))) {
              return;
            }
            try {
              request.accept(sock);
            } catch (Exception e) {
              Log.warn("MarketRegime TWS request error", e);
            }
            try {
              Thread.sleep(REQUEST_SPACING_MS);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          });
    }

    synchronized void subscribe() {
      if (client == null || !client.isConnected()) {
        updateStatus("Not connected — click Connect first");
        return;
      }
      if (!hasBaskets()) {
        updateStatus("No baskets — load a watchlist first");
        return;
      }
      if (subscribed) {
        cancelAllSubscriptions();
      }
      resetSessionData();
      subscribed = true;
      subscribeAllContracts();
    }

    synchronized void unsubscribe() {
      subGeneration++;
      if (client == null || !client.isConnected()) {
        subscribed = false;
        updateStatus("Not connected");
        return;
      }
      cancelAllSubscriptions();
      subscribed = false;
      updateStatus(
          "Unsubscribed (" + settings.twsHost + ":" + settings.twsPort + ") · Ready");
    }

    /** Relance l'abonnement sur les paniers des réglages ; faux s'il n'y a pas d'abonnement actif. */
    synchronized boolean resubscribeIfSubscribed() {
      if (!subscribed || client == null || !client.isConnected()) {
        return false;
      }
      cancelAllSubscriptions();
      resetSessionData();
      subscribeAllContracts();
      return true;
    }

    /**
     * Annule les flux de l'abonnement en cours (identifiants mémorisés à l'abonnement, même si les
     * paniers des réglages ont changé depuis), et seulement les historiques encore en attente.
     */
    private void cancelAllSubscriptions() {
      final int[] ids = subscribedTickerIds;
      final boolean[] histPending = subscribedHistPending;
      final int histBase = subscribedHistBase;
      subscribedTickerIds = new int[0];
      subscribedHistPending = new boolean[0];
      if (client == null || !client.isConnected()) {
        return;
      }
      final EClientSocket sock = client;
      for (int i = 0; i < ids.length; i++) {
        final int tickerId = ids[i];
        enqueue(sock, -1, s -> s.cancelMktData(tickerId));
        if (i < histPending.length && histPending[i]) {
          enqueue(sock, -1, s -> s.cancelHistoricalData(histBase + tickerId));
        }
      }
    }

    /**
     * Slot d'une requête d'historique de l'abonnement en cours, ou -1. Chaque abonnement a sa
     * propre plage d'identifiants : une réponse ou une erreur tardive d'une requête annulée (TWS
     * répond « query cancelled ») ne peut pas être prise pour une requête du nouvel abonnement.
     */
    private int histSlotForReqId(int reqId) {
      int base = histIdBase;
      if (reqId < base || reqId >= base + HIST_REQ_OFFSET) {
        return -1;
      }
      return slotForTickerId(reqId - base);
    }

    private void subscribeAllContracts() {
      if (client == null || !client.isConnected()) {
        return;
      }
      final int gen = ++subGeneration;
      final EClientSocket sock = client;

      final int[] tickerIds = new int[slotCount];
      final Contract[] contracts = new Contract[slotCount];
      int k = 0;
      for (int i = 0; i < riskCount; i++) {
        tickerIds[k] = TICKER_RISK_BASE + i;
        contracts[k] = buildUsStockContract(activeRiskSymbols[i]);
        k++;
      }
      for (int i = 0; i < refugeCount; i++) {
        tickerIds[k] = TICKER_REF_BASE + i;
        contracts[k] = buildUsStockContract(activeRefugeSymbols[i]);
        k++;
      }
      for (int i = 0; i < megaCount; i++) {
        tickerIds[k] = TICKER_MEGA_BASE + i;
        contracts[k] = buildUsStockContract(activeMegaSymbols[i]);
        k++;
      }
      subscribedTickerIds = tickerIds.clone();
      subscribedHistPending = new boolean[slotCount];
      final int histBase = HIST_REQ_OFFSET * (1 + gen % HIST_ID_RANGES);
      subscribedHistBase = histBase;
      histIdBase = histBase;

      // Bascule automatique sur Delayed-Frozen si un flux temps réel manque, temps réel sinon
      enqueue(sock, gen, s -> s.reqMarketDataType(4));

      // 1. Flux temps réel de chaque action, puis 2. historique RTH 1m du jour (barres d'une
      // minute : non soumises aux limites IB des petites barres), le tout dans la file espacée
      for (int i = 0; i < slotCount; i++) {
        if (contracts[i] != null) {
          final int tickerId = tickerIds[i];
          final Contract contract = contracts[i];
          enqueue(sock, gen, s -> s.reqMktData(tickerId, contract, "", false, false, null));
        } else {
          markHistoricalSlotDone(i);
        }
      }
      for (int i = 0; i < slotCount; i++) {
        if (contracts[i] != null) {
          final int tickerId = tickerIds[i];
          final Contract contract = contracts[i];
          subscribedHistPending[i] = true;
          enqueue(
              sock,
              gen,
              s ->
                  s.reqHistoricalData(
                      histBase + tickerId,
                      contract,
                      "",
                      "1 D",
                      "1 min",
                      "TRADES",
                      1,
                      2,
                      false,
                      null));
        }
      }

      updateStatus(
          "Subscribed · Loading RTH history ("
              + histCompletedStocks
              + "/"
              + slotCount
              + ")...");
    }

    private Contract buildUsStockContract(String rawSymbol) {
      String sym = cleanSymbol(rawSymbol);
      if (sym.isEmpty()) {
        return null;
      }
      Contract c = new Contract();
      c.symbol(sym);
      c.secType("STK");
      c.exchange("SMART");
      c.primaryExch("NASDAQ");
      c.currency("USD");
      return c;
    }

    @Override
    public void nextValidId(int orderId) {
      updateStatus(
          "Connected (" + settings.twsHost + ":" + settings.twsPort + ") · Ready");
    }

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
      if (price <= 0.0) {
        return;
      }
      boolean isOpenField = (field == FIELD_OPEN || field == FIELD_DELAYED_OPEN);
      // Le tick Open renvoie à la veille tant que l'ouverture officielle n'est pas publiée : la
      // séance du jour est réinitialisée à 09h30 et son historique ne sert qu'après 09h31:05
      boolean isLastField = (field == FIELD_LAST || field == FIELD_DELAYED_LAST);
      if (!isOpenField && !isLastField) {
        return;
      }

      ZonedDateTime nyNow =
          (isRealtime && chartNanos > 0)
              ? Instant.ofEpochSecond(0, chartNanos).atZone(NY_ZONE)
              : ZonedDateTime.now(NY_ZONE);
      LocalTime nyTime = nyNow.toLocalTime();
      boolean afterRthOpen = !nyTime.isBefore(RTH_OPEN);
      // Un tick LAST ne peut initialiser l'open QUE pendant la toute 1re minute (09h30:00–09h30:59)
      boolean inOpeningMinute = afterRthOpen && nyTime.isBefore(LocalTime.of(9, 31));

      synchronized (MarketRegime.this) {
        if (tickerId >= TICKER_RISK_BASE && tickerId < TICKER_RISK_BASE + riskCount) {
          int idx = tickerId - TICKER_RISK_BASE;
          updateStockSlot(idx, idx, isOpenField, inOpeningMinute, price);
        } else if (tickerId >= TICKER_REF_BASE && tickerId < TICKER_REF_BASE + refugeCount) {
          int idx = riskCount + (tickerId - TICKER_REF_BASE);
          updateStockSlot(idx, idx, isOpenField, inOpeningMinute, price);
        } else if (tickerId >= TICKER_MEGA_BASE && tickerId < TICKER_MEGA_BASE + megaCount) {
          int idx = tickerId - TICKER_MEGA_BASE;
          int histSlot = stockCount + idx;
          if (isOpenField) {
            if (Double.isNaN(histOpen0930[histSlot])) {
              megaOpen[idx] = price;
            }
          } else {
            megaLast[idx] = price;
            if (inOpeningMinute && Double.isNaN(megaOpen[idx])) {
              megaOpen[idx] = price;
            }
          }
        }
      }
    }

    private void updateStockSlot(
        int stockIdx,
        int histSlot,
        boolean isOpenField,
        boolean inOpeningMinute,
        double price) {
      if (isOpenField) {
        if (Double.isNaN(histOpen0930[histSlot])) {
          stockOpen[stockIdx] = price;
        }
      } else {
        stockLast[stockIdx] = price;
        if (inOpeningMinute && Double.isNaN(stockOpen[stockIdx])) {
          stockOpen[stockIdx] = price;
        }
      }
    }

    @Override
    public void historicalData(int reqId, Bar bar) {
      int slot = histSlotForReqId(reqId);
      if (slot < 0 || bar == null || bar.open() <= 0.0 || bar.close() <= 0.0) {
        return;
      }
      long barEpochSec = parseBarEpochSeconds(bar.time());
      if (barEpochSec <= 0L) {
        return;
      }
      ZonedDateTime barNy = Instant.ofEpochSecond(barEpochSec).atZone(NY_ZONE);
      LocalDate barDate = barNy.toLocalDate();
      LocalTime barTime = barNy.toLocalTime();
      if (barTime.isBefore(RTH_OPEN) || !barTime.isBefore(RTH_CLOSE)) {
        return;
      }

      synchronized (MarketRegime.this) {
        // En temps réel, seule la séance du graphique compte : à l'ouverture, TWS peut encore
        // renvoyer la séance de la veille, qui ne doit jamais servir d'ouverture 09h30
        LocalDate rthDate = currentRthDate;
        if (isRealtime && rthDate != null && !barDate.equals(rthDate)) {
          return;
        }
        if (histSessionDate == null || barDate.isAfter(histSessionDate)) {
          histSessionDate = barDate;
          histMinuteCloses.clear();
          Arrays.fill(histFirstBarSec, Long.MAX_VALUE);
          Arrays.fill(histLastBarSec, Long.MIN_VALUE);
          Arrays.fill(histOpen0930, Double.NaN);
          Arrays.fill(histBarCount, 0);
        } else if (barDate.isBefore(histSessionDate)) {
          return;
        }

        // Première barre RTH (09h30 NY) : verrouille le vrai prix d'ouverture 09h30 NY
        if (barEpochSec < histFirstBarSec[slot]) {
          histFirstBarSec[slot] = barEpochSec;
          histOpen0930[slot] = bar.open();
          applySlotOpen(slot, bar.open());
        }
        // Dernière barre RTH reçue : initialise le prix si aucun tick temps réel n'est arrivé
        if (barEpochSec >= histLastBarSec[slot]) {
          histLastBarSec[slot] = barEpochSec;
          applySlotLastIfMissing(slot, bar.close());
        }

        long minuteEpochSec = (barEpochSec / 60L) * 60L;
        double[] row = histMinuteCloses.computeIfAbsent(minuteEpochSec, k -> emptySlotArray());
        row[slot] = bar.close();
        histBarCount[slot]++;
      }
    }

    @Override
    public void historicalDataEnd(int reqId, String start, String end) {
      int slot = histSlotForReqId(reqId);
      if (slot >= 0) {
        markHistoricalSlotDone(slot);
      }
    }

    private void applySlotOpen(int slot, double openPrice) {
      if (slot < stockCount) {
        stockOpen[slot] = openPrice;
      } else {
        megaOpen[slot - stockCount] = openPrice;
      }
    }

    private void applySlotLastIfMissing(int slot, double closePrice) {
      if (slot < stockCount) {
        if (Double.isNaN(stockLast[slot])) {
          stockLast[slot] = closePrice;
        }
      } else {
        int idx = slot - stockCount;
        if (Double.isNaN(megaLast[idx])) {
          megaLast[idx] = closePrice;
        }
      }
    }

    private void markHistoricalSlotDone(int slot) {
      if (slot < 0 || slot >= slotCount) {
        return;
      }
      boolean[] histPending = subscribedHistPending;
      if (slot < histPending.length) {
        histPending[slot] = false;
      }
      boolean shouldRebuild = false;
      boolean missingToday = false;
      int doneCount;
      int totalSlots;
      synchronized (MarketRegime.this) {
        if (!histTickerDone[slot]) {
          histTickerDone[slot] = true;
          histCompletedStocks++;
          shouldRebuild = (histCompletedStocks == slotCount || histCompletedStocks % 5 == 0);
          // Historique complet mais sans la séance du jour (abonnement dans la 1re minute, TWS
          // en retard) : nouvelle demande une minute plus tard, au plus 3 fois par séance
          if (histCompletedStocks == slotCount
              && isRealtime
              && inRegularSession
              && currentRthDate != null
              && !currentRthDate.equals(histSessionDate)
              && histRetriesToday < MAX_HIST_RETRIES) {
            histRetriesToday++;
            missingToday = true;
          }
        }
        doneCount = histCompletedStocks;
        totalSlots = slotCount;
      }
      if (missingToday) {
        long nowSec = chartNanos > 0 ? chartNanos / 1_000_000_000L : Instant.now().getEpochSecond();
        nextHistRequestSec = nowSec + 60L;
      }
      if (shouldRebuild) {
        rebuildHistoryFrom0930();
      }
      if (subscribed) {
        if (doneCount >= totalSlots) {
          updateStatus(
              "Subscribed ("
                  + settings.twsHost
                  + ":"
                  + settings.twsPort
                  + ") · "
                  + totalSlots
                  + " stocks (RTH history "
                  + (missingToday ? "of today not ready yet, retry in 1 min)" : "ready)"));
        } else {
          updateStatus(
              "Subscribed · Loading RTH history (" + doneCount + "/" + slotCount + ")...");
        }
      }
    }

    @Override
    public void connectionClosed() {
      if (active) {
        updateStatus("Connection closed by TWS");
      }
    }

    @Override
    public void error(
        int id,
        long errorTime,
        int errorCode,
        String errorMsg,
        String advancedOrderRejectJson) {
      // Codes 2104/2106/2158/2176 = notifications normales des fermes de données IBKR
      if (errorCode == 2104
          || errorCode == 2106
          || errorCode == 2158
          || errorCode == 2176
          || errorCode == 10167) {
        return;
      }
      lastTwsError = "[" + id + "] (" + errorCode + ") " + errorMsg;
      int histSlot = histSlotForReqId(id);
      if (histSlot >= 0) {
        markHistoricalSlotDone(histSlot);
      }
      Log.info("MarketRegime TWS [" + id + "] (" + errorCode + "): " + errorMsg);
    }
  }

  private void updateStatus(String status) {
    this.connectionStatus = status;
    JLabel label = this.statusLabel;
    if (label != null) {
      SwingUtilities.invokeLater(() -> label.setText(status));
    }
  }

  /** Nettoie un symbole saisi par l'utilisateur (ex. "NASDAQ:NVDA" -> "NVDA"). */
  private static String cleanSymbol(String raw) {
    return Watchlist.cleanSymbol(raw);
  }

  // ======================================================================
  //  Panneau de réglages Bookmap (CustomSettingsPanelProvider)
  // ======================================================================

  @Override
  public StrategyPanel[] getCustomSettingsPanels() {
    // 1. Section Connexion TWS
    JPanel twsPanel = verticalBox();
    JLabel status = new JLabel(connectionStatus);
    this.statusLabel = status;
    twsPanel.add(pair(new JLabel("Status"), status));
    twsPanel.add(gap());
    twsPanel.add(
        pair(
            new JLabel("Host"),
            textField(settings.twsHost, 10, v -> settings.twsHost = v)));
    twsPanel.add(gap());
    twsPanel.add(
        pair(
            new JLabel("Port"),
            intSpinner(settings.twsPort, 1, 65535, 1, v -> settings.twsPort = v)));
    twsPanel.add(gap());
    twsPanel.add(
        pair(
            new JLabel("Client ID"),
            intSpinner(settings.twsClientId, 0, 9999, 1, v -> settings.twsClientId = v)));
    twsPanel.add(gap());
    JButton connectBtn = new JButton("Connect");
    connectBtn.addActionListener(
        e -> {
          saveSettings();
          twsBridge.connect();
        });
    JButton disconnectBtn = new JButton("Disconnect");
    disconnectBtn.addActionListener(e -> twsBridge.disconnect());
    JButton subscribeBtn = new JButton("Subscribe");
    subscribeBtn.addActionListener(
        e -> {
          saveSettings();
          twsBridge.subscribe();
        });
    JButton unsubscribeBtn = new JButton("Unsubscribe");
    unsubscribeBtn.addActionListener(e -> twsBridge.unsubscribe());
    JPanel twsButtons = new JPanel(new GridBagLayout());
    twsButtons.setOpaque(false);
    GridBagConstraints btnGc = new GridBagConstraints();
    btnGc.gridx = 0;
    btnGc.gridy = 0;
    btnGc.fill = GridBagConstraints.HORIZONTAL;
    btnGc.insets = new Insets(0, 0, 6, 6);
    twsButtons.add(connectBtn, btnGc);
    btnGc = new GridBagConstraints();
    btnGc.gridx = 1;
    btnGc.gridy = 0;
    btnGc.fill = GridBagConstraints.HORIZONTAL;
    btnGc.insets = new Insets(0, 0, 6, 0);
    twsButtons.add(disconnectBtn, btnGc);
    btnGc = new GridBagConstraints();
    btnGc.gridx = 0;
    btnGc.gridy = 1;
    btnGc.fill = GridBagConstraints.HORIZONTAL;
    btnGc.insets = new Insets(0, 0, 6, 6);
    twsButtons.add(subscribeBtn, btnGc);
    btnGc = new GridBagConstraints();
    btnGc.gridx = 1;
    btnGc.gridy = 1;
    btnGc.fill = GridBagConstraints.HORIZONTAL;
    btnGc.insets = new Insets(0, 0, 6, 0);
    twsButtons.add(unsubscribeBtn, btnGc);
    JButton snapshotBtn = new JButton("Data Snapshot");
    snapshotBtn.addActionListener(e -> showSnapshotDialog(snapshotBtn));
    btnGc = new GridBagConstraints();
    btnGc.gridx = 0;
    btnGc.gridy = 2;
    btnGc.gridwidth = 2;
    btnGc.fill = GridBagConstraints.HORIZONTAL;
    twsButtons.add(snapshotBtn, btnGc);
    twsPanel.add(pair(new JLabel(""), twsButtons));

    // 2. Section Paramètres du Régime & Corrélations
    JPanel regimeParams = verticalBox();
    regimeParams.add(
        pair(
            new JLabel("Rotation spread ≥ (%)"),
            doubleSpinner(
                settings.spreadThreshold, 0.0, 10.0, 0.05, v -> settings.spreadThreshold = v)));
    regimeParams.add(gap());
    regimeParams.add(
        pair(
            new JLabel("Mega-caps confirm rotations"),
            checkBox(settings.confirmMega, v -> settings.confirmMega = v)));
    regimeParams.add(gap());
    regimeParams.add(
        pair(
            new JLabel("Hysteresis"),
            checkBox(settings.hysteresis, v -> settings.hysteresis = v)));
    regimeParams.add(gap());
    regimeParams.add(
        pair(
            new JLabel("Correlation window (minutes)"),
            intSpinner(
                settings.corrWindowMinutes, 1, 240, 1, v -> settings.corrWindowMinutes = v)));
    regimeParams.add(gap());
    regimeParams.add(
        pair(
            new JLabel("Return sample interval (sec)"),
            intSpinner(
                settings.corrSampleSeconds, 5, 300, 5, v -> settings.corrSampleSeconds = v)));
    regimeParams.add(gap());
    regimeParams.add(
        pair(
            new JLabel("Heatmap auto scale"),
            checkBox(
                settings.heatmapAutoScale,
                v -> {
                  settings.heatmapAutoScale = v;
                  updateAutoScales(true);
                })));
    regimeParams.add(gap());
    regimeParams.add(
        pair(
            new JLabel("Heatmap scale (manual)"),
            doubleSpinner(
                settings.heatmapScale,
                0.05,
                2.00,
                0.05,
                v -> {
                  settings.heatmapScale = v;
                  reprojectHeatmapLines();
                })));

    // 3. Section Couleurs des Courbes
    JPanel lineColors = verticalBox();
    lineColors.add(
        colorRow(
            new Color(settings.chartLineColor),
            DEFAULT_CHART_COLOR,
            "Chart instrument (%)",
            c -> {
              settings.chartLineColor = c.getRGB();
              onColorEdited();
            }));
    lineColors.add(gap());
    lineColors.add(
        colorRow(
            new Color(settings.riskLineColor),
            DEFAULT_RISK_COLOR,
            "Risk basket (%)",
            c -> {
              settings.riskLineColor = c.getRGB();
              onColorEdited();
            }));
    lineColors.add(gap());
    lineColors.add(
        colorRow(
            new Color(settings.refugeLineColor),
            DEFAULT_REFUGE_COLOR,
            "Refuge basket (%)",
            c -> {
              settings.refugeLineColor = c.getRGB();
              onColorEdited();
            }));
    lineColors.add(gap());
    lineColors.add(
        colorRow(
            new Color(settings.megaLineColor),
            DEFAULT_MEGA_COLOR,
            "Mega-cap basket (%)",
            c -> {
              settings.megaLineColor = c.getRGB();
              onColorEdited();
            }));
    lineColors.add(gap());
    lineColors.add(
        colorRow(
            new Color(settings.spreadLineColor),
            DEFAULT_SPREAD_COLOR,
            "Refuge − Risk (%)",
            c -> {
              settings.spreadLineColor = c.getRGB();
              onColorEdited();
            }));
    lineColors.add(gap());
    lineColors.add(
        colorRow(
            new Color(settings.crossLineColor),
            DEFAULT_CROSS_COLOR,
            "Cross correlation",
            c -> {
              settings.crossLineColor = c.getRGB();
              onColorEdited();
            }));
    lineColors.add(gap());
    lineColors.add(
        colorRow(
            new Color(settings.withinLineColor),
            DEFAULT_WITHIN_COLOR,
            "Within correlation",
            c -> {
              settings.withinLineColor = c.getRGB();
              onColorEdited();
            }));

    // 4. Section Couleurs des 5 Régimes
    JPanel regimeColors = verticalBox();
    regimeColors.add(
        colorRow(
            new Color(settings.rotRiskRegimeColor),
            DEFAULT_ROT_RISK_COLOR,
            "Rotation to risk (+2)",
            c -> {
              settings.rotRiskRegimeColor = c.getRGB();
              onColorEdited();
            }));
    regimeColors.add(gap());
    regimeColors.add(
        colorRow(
            new Color(settings.slowLongRegimeColor),
            DEFAULT_SLOW_LONG_COLOR,
            "Slow long (+1)",
            c -> {
              settings.slowLongRegimeColor = c.getRGB();
              onColorEdited();
            }));
    regimeColors.add(gap());
    regimeColors.add(
        colorRow(
            new Color(settings.neutralRegimeColor),
            DEFAULT_NEUTRAL_COLOR,
            "Neutral (0)",
            c -> {
              settings.neutralRegimeColor = c.getRGB();
              onColorEdited();
            }));
    regimeColors.add(gap());
    regimeColors.add(
        colorRow(
            new Color(settings.rotRefugeRegimeColor),
            DEFAULT_ROT_REFUGE_COLOR,
            "Rotation to refuge (-1)",
            c -> {
              settings.rotRefugeRegimeColor = c.getRGB();
              onColorEdited();
            }));
    regimeColors.add(gap());
    regimeColors.add(
        colorRow(
            new Color(settings.liquidationRegimeColor),
            DEFAULT_LIQUIDATION_COLOR,
            "Liquidation (-2)",
            c -> {
              settings.liquidationRegimeColor = c.getRGB();
              onColorEdited();
            }));

    // 5. Section Watchlist CSV (exportée par Quant Research)
    JPanel watchlistPanel = verticalBox();
    FittingLabel watchlistStatusText = new FittingLabel(watchlistStatus, false);
    this.watchlistStatusLabel = watchlistStatusText;
    watchlistPanel.add(watchlistStatusText);
    watchlistPanel.add(gap());
    JTextField watchlistField =
        textField(settings.watchlistPath, 16, v -> settings.watchlistPath = v.trim());
    watchlistPanel.add(pair(new JLabel("File"), watchlistField));
    watchlistPanel.add(gap());
    JButton browseBtn = new JButton("Browse…");
    browseBtn.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setFileFilter(new FileNameExtensionFilter("CSV watchlist", "csv", "txt"));
          if (!settings.watchlistPath.isBlank()) {
            chooser.setSelectedFile(new File(settings.watchlistPath));
          }
          if (chooser.showOpenDialog(browseBtn) == JFileChooser.APPROVE_OPTION) {
            watchlistField.setText(chooser.getSelectedFile().getAbsolutePath());
            loadWatchlist(true);
          }
        });
    JButton loadBtn = new JButton("Load watchlist");
    loadBtn.addActionListener(e -> loadWatchlist(true));
    JPanel watchlistButtons = new JPanel(new GridBagLayout());
    watchlistButtons.setOpaque(false);
    GridBagConstraints wlGc = new GridBagConstraints();
    wlGc.gridx = 0;
    wlGc.gridy = 0;
    wlGc.insets = new Insets(0, 0, 0, 6);
    watchlistButtons.add(browseBtn, wlGc);
    wlGc = new GridBagConstraints();
    wlGc.gridx = 1;
    wlGc.gridy = 0;
    watchlistButtons.add(loadBtn, wlGc);
    watchlistPanel.add(pair(new JLabel(""), watchlistButtons));

    // Paniers chargés (lecture seule) : la watchlist CSV est la seule source des paniers
    watchlistPanel.add(gap());
    FittingLabel basketsView = new FittingLabel("", false);
    Runnable refreshBaskets =
        () ->
            basketsView.setText(
                !hasBaskets()
                    ? "<html><i>No baskets loaded.</i></html>"
                    : "<html><b>Risk ("
                    + settings.riskSymbols.length
                    + ")</b> "
                    + String.join(", ", settings.riskSymbols)
                    + "<br><b>Refuge ("
                    + settings.refugeSymbols.length
                    + ")</b> "
                    + String.join(", ", settings.refugeSymbols)
                    + "<br><b>Mega-cap ("
                    + settings.megaSymbols.length
                    + ")</b> "
                    + formatMegaBasket(settings.megaSymbols, settings.megaWeights)
                    + "</html>");
    refreshBaskets.run();
    this.basketFieldsRefresher = refreshBaskets;
    watchlistPanel.add(basketsView);

    JPanel about = verticalBox();
    about.add(new FittingLabel("Market Regime " + VERSION, false));

    return new StrategyPanel[] {
      section("TWS Connection", twsPanel, 6, TEXT_INDENT),
      section("Regime & Correlations", regimeParams, 6, TEXT_INDENT),
      section("Line Colors", lineColors, 4, 0),
      section("Regime Colors", regimeColors, 4, 0),
      section("Baskets (watchlist CSV)", watchlistPanel, 6, TEXT_INDENT),
      section("About", about, 4, TEXT_INDENT)
    };
  }

  private static String formatMegaBasket(String[] symbols, double[] weights) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < symbols.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(symbols[i]).append(" (").append(String.format(Locale.US, "%.2f", weights[i])).append(')');
    }
    return sb.toString();
  }

  private void onColorEdited() {
    saveSettings();
    applyColors();
  }

  private void saveSettings() {
    if (api != null) {
      api.setSettings(settings);
    }
  }

  private static void sanitizeSettings(Settings s) {
    if (s.twsHost == null || s.twsHost.isBlank()) {
      s.twsHost = "127.0.0.1";
    }
    // Les paniers ne viennent que d'une watchlist : les trois sont valides ensemble, sinon aucun
    String[] risk = sanitizeBasket(s.riskSymbols, MAX_RISK, MIN_BASKET_STOCKS);
    String[] refuge = sanitizeBasket(s.refugeSymbols, MAX_REFUGE, MIN_BASKET_STOCKS);
    String[] mega = sanitizeBasket(s.megaSymbols, MAX_MEGA, MIN_MEGA_STOCKS);
    boolean weightsValid =
        s.megaWeights != null
            && s.megaWeights.length == mega.length
            && Arrays.stream(s.megaWeights).allMatch(w -> w > 0.0);
    if (risk.length == 0 || refuge.length == 0 || mega.length == 0 || !weightsValid) {
      clearBaskets(s);
    } else {
      s.riskSymbols = risk;
      s.refugeSymbols = refuge;
      s.megaSymbols = mega;
    }
    if (s.watchlistPath == null) {
      s.watchlistPath = "";
    }
    if (s.corrWindowMinutes < 1) {
      s.corrWindowMinutes = 20;
    }
    if (s.corrSampleSeconds < 5) {
      s.corrSampleSeconds = 15;
    }
    if (s.heatmapScale <= 0.0 || Double.isNaN(s.heatmapScale)) {
      s.heatmapScale = 0.25;
    }
    if (s.chartLineColor == 0) {
      s.chartLineColor = DEFAULT_CHART_COLOR.getRGB();
    }
  }

  /**
   * Symboles nettoyés, sans doublon et plafonnés à {@code max} ; un panier vide s'il en reste moins
   * de {@code min}.
   */
  private static String[] sanitizeBasket(String[] symbols, int max, int min) {
    if (symbols == null) {
      return new String[0];
    }
    LinkedHashSet<String> clean = new LinkedHashSet<>();
    for (String raw : symbols) {
      String sym = cleanSymbol(raw);
      if (!sym.isEmpty() && clean.size() < max) {
        clean.add(sym);
      }
    }
    return clean.size() < min ? new String[0] : clean.toArray(new String[0]);
  }

  private static void clearBaskets(Settings s) {
    s.riskSymbols = new String[0];
    s.refugeSymbols = new String[0];
    s.megaSymbols = new String[0];
    s.megaWeights = new double[0];
  }

  private boolean hasBaskets() {
    return settings.riskSymbols.length > 0
        && settings.refugeSymbols.length > 0
        && settings.megaSymbols.length > 0;
  }

  /**
   * Charge la watchlist CSV des réglages dans les paniers. En cas d'erreur, les paniers actuels
   * restent en place et le statut l'indique. Avec {@code applyNow}, l'abonnement TWS en cours est
   * relancé sur les nouveaux paniers.
   */
  private void loadWatchlist(boolean applyNow) {
    String pathText = settings.watchlistPath == null ? "" : settings.watchlistPath.trim();
    if (pathText.isEmpty()) {
      setWatchlistStatus("No watchlist file: choose a CSV file with Browse…");
      return;
    }
    Watchlist list;
    try {
      list = Watchlist.read(Paths.get(pathText), MAX_RISK, MAX_REFUGE, MAX_MEGA);
    } catch (Watchlist.WatchlistException | InvalidPathException e) {
      setWatchlistStatus(
          (hasBaskets() ? "Not loaded, last loaded baskets kept: " : "Not loaded: ")
              + e.getMessage());
      Log.info("MarketRegime watchlist: " + e.getMessage());
      return;
    }
    settings.riskSymbols = list.riskSymbols;
    settings.refugeSymbols = list.refugeSymbols;
    settings.megaSymbols = list.megaSymbols;
    settings.megaWeights = list.megaWeights;
    saveSettings();

    // Paniers actifs tout de suite : abonnement TWS relancé s'il tourne, sinon les nouveaux
    // paniers sont figés pour le prochain Subscribe (et visibles dans le Data Snapshot)
    String applied;
    if (applyNow && twsBridge.resubscribeIfSubscribed()) {
      applied = "applied, TWS subscription restarted";
    } else {
      resetSessionData();
      applied = "applied, used at next Subscribe";
    }

    String loadedAt =
        ZonedDateTime.now(NY_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'NY'"));
    StringBuilder status =
        new StringBuilder()
            .append("Loaded ")
            .append(loadedAt)
            .append(" · ")
            .append(list.riskSymbols.length)
            .append(" Risk · ")
            .append(list.refugeSymbols.length)
            .append(" Refuge · ")
            .append(settings.megaSymbols.length)
            .append(" Mega")
            .append(" · ")
            .append(applied);
    if (!list.warnings.isEmpty()) {
      status.append(" · ").append(String.join(" · ", list.warnings));
    }
    setWatchlistStatus(status.toString());

    Runnable refresher = basketFieldsRefresher;
    if (refresher != null) {
      SwingUtilities.invokeLater(refresher);
    }
  }

  private void setWatchlistStatus(String status) {
    this.watchlistStatus = status;
    JLabel label = this.watchlistStatusLabel;
    if (label != null) {
      SwingUtilities.invokeLater(() -> label.setText("<html>" + status + "</html>"));
    }
  }

  private void showSnapshotDialog(Component parent) {
    Window owner = SwingUtilities.getWindowAncestor(parent);
    JDialog dialog = new JDialog(owner, "Market Regime · TWS Data Snapshot");
    dialog.setLayout(new BorderLayout(8, 8));

    JTextArea area = new JTextArea(buildSnapshotText(), 28, 76);
    area.setEditable(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    JPanel bottomBar = new JPanel(new GridBagLayout());
    bottomBar.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
    JButton refreshBtn = new JButton("Refresh");
    refreshBtn.addActionListener(
        e -> {
          area.setText(buildSnapshotText());
          area.setCaretPosition(0);
        });
    JButton copyBtn = new JButton("Copy to clipboard");
    copyBtn.addActionListener(
        e ->
            Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(area.getText()), null));

    GridBagConstraints gc = new GridBagConstraints();
    gc.gridx = 0;
    gc.gridy = 0;
    gc.insets = new Insets(0, 0, 0, 6);
    bottomBar.add(refreshBtn, gc);
    gc = new GridBagConstraints();
    gc.gridx = 1;
    gc.gridy = 0;
    bottomBar.add(copyBtn, gc);

    dialog.add(new JScrollPane(area), BorderLayout.CENTER);
    dialog.add(bottomBar, BorderLayout.SOUTH);
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
  }

  private synchronized String buildSnapshotText() {
    StringBuilder sb = new StringBuilder(2048);
    ZonedDateTime nyNow =
        (isRealtime && chartNanos > 0)
            ? Instant.ofEpochSecond(0, chartNanos).atZone(NY_ZONE)
            : ZonedDateTime.now(NY_ZONE);
    String timeStr = nyNow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));

    double riskPerf = calcBasketPerf(0, riskCount);
    double refPerf = calcBasketPerf(riskCount, stockCount);
    double megaPerf = calcMegaPerf();
    double chartPerf = calcStockPerf(chartOpen0930, chartLast);
    double spread =
        (!Double.isNaN(refPerf) && !Double.isNaN(riskPerf)) ? refPerf - riskPerf : Double.NaN;
    double[] corrs = calcCorrelationsFromWindow(null);

    sb.append("TWS Status     : ").append(connectionStatus).append('\n');
    sb.append("Last TWS Error : ").append(lastTwsError).append('\n');
    sb.append("New York Time  : ")
        .append(timeStr)
        .append(inRegularSession ? " (RTH OPEN)" : " (OUTSIDE RTH)")
        .append('\n');
    sb.append("RTH History    : ")
        .append(histCompletedStocks)
        .append('/')
        .append(slotCount)
        .append(" instruments · ")
        .append(histMinuteCloses.size())
        .append(" TWS bars (1m) · ")
        .append(chartMinuteCloses.size())
        .append(" Bookmap bars (1m)\n");
    sb.append("Active Regime  : ")
        .append(currentRegime.label)
        .append(" (")
        .append(fmtNum(currentRegime.score))
        .append(") · Risk<Chart seen: ")
        .append(wasRiskBelowChart)
        .append('\n');
    sb.append("Correlations   : Within=")
        .append(fmtNum(corrs[0]))
        .append(" · Cross=")
        .append(fmtNum(corrs[1]))
        .append(" (")
        .append(completedReturnRows.size())
        .append(" steps in ")
        .append(settings.corrWindowMinutes)
        .append("m)\n");
    sb.append(
        String.format(
            Locale.US,
            "Heatmap Scale  : %s · Risk x%.2f · Refuge x%.2f · Mega x%.2f%n",
            settings.heatmapAutoScale ? "Auto" : "Manual",
            heatmapScaleFor(0),
            heatmapScaleFor(1),
            heatmapScaleFor(2)));
    sb.append("Baskets        : ")
        .append(riskCount)
        .append(" Risk · ")
        .append(refugeCount)
        .append(" Refuge · ")
        .append(megaCount)
        .append(" Mega (")
        .append(slotCount)
        .append(" TWS lines)\n");
    sb.append("Watchlist      : ").append(watchlistStatus).append("\n\n");

    sb.append("SUMMARY (% since 09:30 NY)\n");
    sb.append("------------------------------------------------------------\n");
    sb.append(
        String.format(
            Locale.US,
            "Chart        : %s (%s, 09:30 open %s)%n",
            fmtPct(chartPerf),
            chartAlias,
            !chartOpenLocked
                ? "unknown"
                : chartOpenEstimated ? "estimated from baskets" : "from Bookmap trades"));
    sb.append(String.format(Locale.US, "Risk Basket  : %s%n", fmtPct(riskPerf)));
    sb.append(String.format(Locale.US, "Refuge Basket: %s%n", fmtPct(refPerf)));
    sb.append(String.format(Locale.US, "Mega Basket  : %s%n", fmtPct(megaPerf)));
    sb.append(String.format(Locale.US, "Refuge - Risk: %s%n%n", fmtPct(spread)));

    sb.append(
        String.format(
            Locale.US,
            "%-10s %-8s %12s %12s %10s   %-14s%n",
            "BASKET",
            "SYMBOL",
            "OPEN (09:30)",
            "LAST",
            "PERF (%)",
            "STATE"));
    sb.append("------------------------------------------------------------------------\n");
    appendTickerRow(
        sb,
        "CHART",
        chartAlias,
        chartOpen0930 * instrumentPips,
        chartLast * instrumentPips,
        chartMinuteCloses.size() + "m");
    for (int i = 0; i < riskCount; i++) {
      appendTickerRow(
          sb,
          "RISK " + (i + 1),
          activeRiskSymbols[i],
          stockOpen[i],
          stockLast[i],
          histBarCount[i] + "m");
    }
    for (int i = 0; i < refugeCount; i++) {
      int slot = riskCount + i;
      appendTickerRow(
          sb,
          "REFUGE " + (i + 1),
          activeRefugeSymbols[i],
          stockOpen[slot],
          stockLast[slot],
          histBarCount[slot] + "m");
    }
    for (int i = 0; i < megaCount; i++) {
      int histSlot = stockCount + i;
      String extra =
          String.format(Locale.US, "w=%.2f %dm", activeMegaWeights[i], histBarCount[histSlot]);
      appendTickerRow(
          sb, "MEGA " + (i + 1), activeMegaSymbols[i], megaOpen[i], megaLast[i], extra);
    }
    return sb.toString();
  }

  private static void appendTickerRow(
      StringBuilder sb, String group, String sym, double open, double last, String note) {
    double perf = calcStockPerf(open, last);
    boolean ok = !Double.isNaN(perf);
    String state = ok ? ("OK " + note).trim() : ("MISSING " + note).trim();
    sb.append(
        String.format(
            Locale.US,
            "%-10s %-8s %12s %12s %10s   %-14s%n",
            group,
            sym == null ? "" : sym,
            fmtNum(open),
            fmtNum(last),
            fmtPct(perf),
            state));
  }

  private static String fmtNum(double v) {
    return Double.isNaN(v) ? "NaN" : String.format(Locale.US, "%.2f", v);
  }

  private static String fmtPct(double v) {
    return Double.isNaN(v) ? "NaN" : String.format(Locale.US, "%+.2f%%", v);
  }

  // ======================================================================
  //  Composants Swing adaptés au dialogue de réglages Bookmap
  // ======================================================================

  private static StrategyPanel section(String title, JPanel body, int top, int left) {
    body.setBorder(BorderFactory.createEmptyBorder(top, left, 6, 4));
    body.setOpaque(false);
    StrategyPanel panel = new Section(title);
    panel.add(body, BorderLayout.CENTER);
    return panel;
  }

  private static final class Section extends StrategyPanel {
    private static final int MAX_ASKED_WIDTH = 372;

    Section(String title) {
      super(title, new BorderLayout());
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      return new Dimension(Math.min(preferred.width, MAX_ASKED_WIDTH), preferred.height);
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(0, 0);
    }

    @Override
    public Dimension getMaximumSize() {
      return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public void addNotify() {
      super.addNotify();
      SwingUtilities.invokeLater(
          () -> {
            revalidate();
            if (getParent() != null) {
              getParent().revalidate();
            }
          });
    }
  }

  private static final class ColorRow extends ColorsConfigItem {
    ColorRow(Color current, Color fallback, String name, Consumer<Color> onPick) {
      super(current, fallback, name, onPick);
      setAlignmentX(Component.LEFT_ALIGNMENT);
      JLabel label = getLabel();
      if (label != null) {
        label.setPreferredSize(null);
      }
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      Dimension minimum = super.getMinimumSize();
      return new Dimension(preferred.width, Math.max(preferred.height, minimum.height));
    }
  }

  private static ColorRow colorRow(
      Color current, Color fallback, String name, Consumer<Color> onPick) {
    return new ColorRow(current, fallback, name, onPick);
  }

  private static final class FittingLabel extends JLabel {
    FittingLabel(String text, boolean bold) {
      super("<html>" + text + "</html>");
      setAlignmentX(Component.LEFT_ALIGNMENT);
      if (bold) {
        setFont(getFont().deriveFont(Font.BOLD));
      }
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      int width = getWidth();
      if (width <= 0 && getParent() != null) {
        width = getParent().getWidth() - parentInsets();
      }
      int inner = width - getInsets().left - getInsets().right;
      if (inner <= 0 || inner >= preferred.width) {
        return preferred;
      }
      View html = (View) getClientProperty(BasicHTML.propertyKey);
      if (html == null) {
        return preferred;
      }
      html.setSize(inner, Integer.MAX_VALUE);
      int height =
          (int) Math.ceil(html.getPreferredSpan(View.Y_AXIS))
              + getInsets().top
              + getInsets().bottom;
      return new Dimension(inner, Math.max(preferred.height, height));
    }

    private int parentInsets() {
      if (!(getParent() instanceof JComponent parent)) {
        return 0;
      }
      Insets insets = parent.getInsets();
      return insets.left + insets.right;
    }
  }

  private JTextField textField(String initial, int columns, Consumer<String> setter) {
    JTextField field = new JTextField(initial, columns);
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                setter.accept(field.getText());
                saveSettings();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                setter.accept(field.getText());
                saveSettings();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                setter.accept(field.getText());
                saveSettings();
              }
            });
    return field;
  }

  private JSpinner intSpinner(int initial, int min, int max, int step, IntConsumer setter) {
    JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, min, max, step));
    JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#");
    editor.getTextField().setColumns(5);
    spinner.setEditor(editor);
    spinner.addChangeListener(
        e -> {
          setter.accept(((Number) spinner.getValue()).intValue());
          saveSettings();
        });
    return spinner;
  }

  private JSpinner doubleSpinner(
      double initial, double min, double max, double step, DoubleConsumer setter) {
    JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, min, max, step));
    JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0.00");
    editor.getTextField().setColumns(5);
    spinner.setEditor(editor);
    spinner.addChangeListener(
        e -> {
          setter.accept(((Number) spinner.getValue()).doubleValue());
          saveSettings();
        });
    return spinner;
  }

  private JCheckBox checkBox(boolean initial, Consumer<Boolean> setter) {
    JCheckBox box = new JCheckBox();
    box.setSelected(initial);
    box.setOpaque(false);
    box.addActionListener(
        e -> {
          setter.accept(box.isSelected());
          saveSettings();
        });
    return box;
  }

  private static JPanel verticalBox() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setOpaque(false);
    return panel;
  }

  private static Component gap() {
    JComponent gap = (JComponent) Box.createVerticalStrut(6);
    gap.setAlignmentX(Component.LEFT_ALIGNMENT);
    return gap;
  }

  private static JPanel pair(JComponent label, JComponent control) {
    JPanel row = new JPanel(new GridBagLayout());
    row.setOpaque(false);
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    GridBagConstraints g = new GridBagConstraints();
    g.gridx = 0;
    g.gridy = 0;
    g.weightx = 1;
    g.anchor = GridBagConstraints.LINE_START;
    g.fill = GridBagConstraints.HORIZONTAL;
    g.insets = new Insets(0, 0, 0, 8);
    row.add(label, g);
    g = new GridBagConstraints();
    g.gridx = 1;
    g.gridy = 0;
    g.anchor = GridBagConstraints.LINE_END;
    row.add(control, g);
    return row;
  }

  private static String readVersion() {
    try (InputStream in = MarketRegime.class.getResourceAsStream("version.txt")) {
      if (in != null) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
    } catch (Exception ignored) {
      // Ignoré
    }
    return "1.0.2";
  }
}
