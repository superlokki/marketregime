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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
 * Régime de marché intraday sous un graphique NQ/MNQ, connecté à TWS API en streaming :
 *
 * <ul>
 *   <li>Sur la Heatmap ({@link GraphType#PRIMARY}) : courbes continues depuis l'ouverture RTH
 *       (09h30 New York) de l'indice NDX, du panier Risk (9 actions), du panier Refuge (7 actions)
 *       et du panier Mega-cap (5 actions pondérées), projetées sur l'échelle de prix NQ/MNQ.
 *   <li>Dans le Bottom Chart et le panneau des Widgets ({@link GraphType#BOTTOM}) : Market Regime
 *       (de -2 à +2, coloré dynamiquement selon le régime actif), écart Refuge − Risk (%),
 *       variations directes en % (NDX, Risk, Refuge, Mega-cap) et corrélations Cross / Within.
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
        CustomSettingsPanelProvider {

  private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
  private static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
  private static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

  private static final int RISK_COUNT = 9;
  private static final int REFUGE_COUNT = 7;
  private static final int STOCK_COUNT = RISK_COUNT + REFUGE_COUNT;
  private static final int MEGA_COUNT = 5;
  private static final int STOCK_SLOTS = STOCK_COUNT + MEGA_COUNT;
  private static final int SLOT_NDX = STOCK_SLOTS;
  private static final int TOTAL_SLOTS = STOCK_SLOTS + 1;
  private static final int MIN_BASKET_STOCKS = 5;
  private static final int MIN_MEGA_STOCKS = 3;
  private static final int MIN_CORR_STEPS = 3;

  private static final double MIN_ACTIVE_SHARE = 0.5;
  private static final double MAX_MEGA_CHART_GAP = 0.20;
  private static final double PERF_HYSTERESIS = 0.10;
  private static final double SPREAD_EXIT_RATIO = 0.8;

  private static final int TICKER_NDX = 100;
  private static final int TICKER_RISK_BASE = 200;
  private static final int TICKER_REF_BASE = 300;
  private static final int TICKER_MEGA_BASE = 400;
  private static final int HIST_REQ_OFFSET = 1000;

  // Tick types TWS API (com.ib.client.TickType)
  private static final int FIELD_LAST = 4;
  private static final int FIELD_OPEN = 14;
  private static final int FIELD_LAST_RTH = 57;
  private static final int FIELD_DELAYED_LAST = 68;
  private static final int FIELD_DELAYED_OPEN = 76;

  // Couleurs par défaut alignées sur market regime.pine
  private static final Color DEFAULT_NDX_COLOR = new Color(0xFF, 0xEB, 0x3B);
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

  private static final String[] DEFAULT_RISK_SYMBOLS = {
    "ALAB", "ARM", "CRWV", "KLAC", "LRCX", "MRVL", "NBIS", "SNDK", "WDC"
  };
  private static final String[] DEFAULT_REFUGE_SYMBOLS = {
    "ADBE", "ADP", "CTAS", "INTU", "ROP", "TRI", "WDAY"
  };
  private static final String[] DEFAULT_MEGA_SYMBOLS = {"NVDA", "AAPL", "MSFT", "GOOGL", "META"};
  private static final double[] DEFAULT_MEGA_WEIGHTS = {5.34, 5.03, 3.96, 4.07, 1.81};

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

    /** Symboles des 9 actions du panier Risk. */
    public String[] riskSymbols = DEFAULT_RISK_SYMBOLS.clone();

    /** Symboles des 7 actions du panier Refuge. */
    public String[] refugeSymbols = DEFAULT_REFUGE_SYMBOLS.clone();

    /** Symboles des 5 actions du panier Mega-cap. */
    public String[] megaSymbols = DEFAULT_MEGA_SYMBOLS.clone();

    /** Poids des 5 actions du panier Mega-cap (capitalisation en T$). */
    public double[] megaWeights = DEFAULT_MEGA_WEIGHTS.clone();

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

    /** Couleur de la courbe NDX (Heatmap & Bottom Chart). */
    public int ndxLineColor = DEFAULT_NDX_COLOR.getRGB();

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

  // Indicateurs Heatmap (GraphType.PRIMARY : courbes activées, Widgets désactivés)
  private IndicatorModifiable ndxHeatmapIndicator;
  private IndicatorModifiable riskHeatmapIndicator;
  private IndicatorModifiable refugeHeatmapIndicator;
  private IndicatorModifiable megaHeatmapIndicator;

  // Indicateurs Bottom Chart & Widgets en % (GraphType.BOTTOM : courbes + Widgets activés)
  private IndicatorModifiable ndxIndicator;
  private IndicatorModifiable riskIndicator;
  private IndicatorModifiable refugeIndicator;
  private IndicatorModifiable megaIndicator;

  // Indicateurs Bottom Chart & Widgets (GraphType.BOTTOM : courbes + Widgets activés)
  private IndicatorModifiable regimeIndicator;
  private IndicatorModifiable spreadIndicator;
  private IndicatorModifiable crossIndicator;
  private IndicatorModifiable withinIndicator;

  // État horloge & prix NQ/MNQ dans Bookmap
  private volatile long chartNanos;
  private long lastTimestampSec = Long.MIN_VALUE;
  private volatile boolean isRealtime;
  private volatile LocalDate currentRthDate;
  private volatile boolean inRegularSession;
  private volatile double nqOpenLevel0930 = Double.NaN;
  private volatile boolean nqOpen0930Locked;
  private volatile double lastNqLevel = Double.NaN;
  private final TreeMap<Long, Double> nqMinuteCloses = new TreeMap<>();

  // État des prix reçus de TWS (index 0..8 = Risk, 9..15 = Refuge, Mega, et indice NDX)
  private double ndxOpen = Double.NaN;
  private double ndxLast = Double.NaN;
  private final double[] stockOpen = new double[STOCK_COUNT];
  private final double[] stockLast = new double[STOCK_COUNT];
  private final double[] megaOpen = new double[MEGA_COUNT];
  private final double[] megaLast = new double[MEGA_COUNT];

  // Historique RTH 1m depuis 09h30 NY (0..8 = Risk, 9..15 = Refuge, 16..20 = Mega, 21 = NDX)
  private LocalDate histSessionDate;
  private final TreeMap<Long, double[]> histMinuteCloses = new TreeMap<>();
  private final long[] histFirstBarSec = new long[TOTAL_SLOTS];
  private final long[] histLastBarSec = new long[TOTAL_SLOTS];
  private final double[] histOpen0930 = new double[TOTAL_SLOTS];
  private final int[] histBarCount = new int[TOTAL_SLOTS];
  private final boolean[] histTickerDone = new boolean[TOTAL_SLOTS];
  private int histCompletedTickers;
  private int histCompletedStocks;
  private volatile String lastTwsError = "None";

  // Fenêtre glissante des rendements pour les corrélations temps réel sans délai
  private final Deque<TimedReturnRow> completedReturnRows = new ArrayDeque<>();
  private final double[] stepStartPrices = new double[STOCK_COUNT];
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
    Settings loaded = api.getSettings(Settings.class);
    if (loaded != null) {
      sanitizeSettings(loaded);
      this.settings = loaded;
    }
    resetSessionData();

    WidgetDisplayInfo symmetricZero = new WidgetDisplayInfo(WidgetDisplayInfo.Type.SYMMETRIC, 0.0);

    // 1. Courbes sur la Heatmap (GraphType.PRIMARY, sans Widgets pour éviter les prix projetés)
    ndxHeatmapIndicator =
        api.registerIndicatorModifiable(
            "NDX (Heatmap)", GraphType.PRIMARY, Double.NaN, true, false);
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
    ndxIndicator =
        api.registerIndicatorModifiable("NDX", GraphType.BOTTOM, Double.NaN, true, true);
    ndxIndicator.setWidgetRules(new WidgetRules(-2.0, 2.0, symmetricZero));

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
    perfAxisGroup.add(ndxIndicator);
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
    twsBridge.disconnect();
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
          nqMinuteCloses.clear();
        }
        if (nyTime.isBefore(LocalTime.of(9, 31)) && !Double.isNaN(lastNqLevel)) {
          nqOpenLevel0930 = lastNqLevel;
          nqOpen0930Locked = true;
        } else {
          nqOpenLevel0930 = Double.NaN;
          nqOpen0930Locked = false;
        }
      }
      if (isRealtime && histSessionDate != null && !nyDate.equals(histSessionDate)) {
        onNewRegularSessionOpen();
      }
    }
  }

  @Override
  public void onTrade(double price, int size, TradeInfo tradeInfo) {
    lastNqLevel = price;
    long ts = chartNanos;
    if (ts > 0) {
      long minuteSec = (ts / 1_000_000_000L / 60L) * 60L;
      synchronized (this) {
        nqMinuteCloses.put(minuteSec, price);
      }
      if (inRegularSession && !nqOpen0930Locked) {
        ZonedDateTime ny = Instant.ofEpochSecond(0, ts).atZone(NY_ZONE);
        if (ny.toLocalTime().isBefore(LocalTime.of(9, 31))) {
          nqOpenLevel0930 = price;
          nqOpen0930Locked = true;
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
    if (histCompletedStocks < STOCK_SLOTS) {
      return;
    }

    double riskPerf = calcBasketPerf(0, RISK_COUNT);
    double refPerf = calcBasketPerf(RISK_COUNT, STOCK_COUNT);
    double megaPerf = calcMegaPerf();
    double compositePerf = calcCompositeStockPerf(riskPerf, refPerf, megaPerf);
    double ndxPerf = calcStockPerf(ndxOpen, ndxLast);
    ensureNqOpen0930(!Double.isNaN(ndxPerf) ? ndxPerf : compositePerf);

    // NDX est la référence principale du régime de marché (repli sur composite si NDX absent)
    double chartPerf = !Double.isNaN(ndxPerf) ? ndxPerf : compositePerf;
    double basketSpread =
        (!Double.isNaN(refPerf) && !Double.isNaN(riskPerf)) ? refPerf - riskPerf : Double.NaN;

    double[] corrs = updateAndCalcRealtimeCorrelations();
    double withinCorr = corrs[0];
    double crossCorr = corrs[1];

    updateRegimeState(chartPerf, riskPerf, refPerf, megaPerf);

    // 1. Tracé continu des courbes sur la Heatmap depuis 09h30 NY
    ndxHeatmapIndicator.addPoint(projectFromOpen0930(ndxPerf));
    riskHeatmapIndicator.addPoint(projectFromOpen0930(riskPerf));
    refugeHeatmapIndicator.addPoint(projectFromOpen0930(refPerf));
    megaHeatmapIndicator.addPoint(projectFromOpen0930(megaPerf));

    // 2. Tracé des variations directes en % depuis 09h30 NY (Bottom Chart + Widgets)
    ndxIndicator.addPoint(ndxPerf);
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

  private void ensureNqOpen0930(double fallbackPerf) {
    if (!nqOpen0930Locked && !Double.isNaN(lastNqLevel) && !Double.isNaN(fallbackPerf)) {
      nqOpenLevel0930 = lastNqLevel / (1.0 + fallbackPerf / 100.0);
      nqOpen0930Locked = true;
    }
  }

  private LocalDate resolveChartRthDate() {
    if (isRealtime && currentRthDate != null) {
      return currentRthDate;
    }
    if (!nqMinuteCloses.isEmpty()) {
      return Instant.ofEpochSecond(nqMinuteCloses.lastKey()).atZone(NY_ZONE).toLocalDate();
    }
    if (histSessionDate != null) {
      return histSessionDate;
    }
    if (currentRthDate != null) {
      return currentRthDate;
    }
    return ZonedDateTime.now(NY_ZONE).toLocalDate();
  }

  private double projectFromOpen0930(double perfPercent) {
    if (Double.isNaN(perfPercent)) {
      return Double.NaN;
    }
    double baseLevel =
        (!Double.isNaN(nqOpenLevel0930) && nqOpenLevel0930 > 0.0) ? nqOpenLevel0930 : lastNqLevel;
    if (Double.isNaN(baseLevel) || baseLevel <= 0.0) {
      return Double.NaN;
    }
    return baseLevel * (1.0 + (perfPercent * settings.heatmapScale) / 100.0);
  }

  private void plotNaN() {
    if (riskIndicator == null) {
      return;
    }
    ndxHeatmapIndicator.addPoint(Double.NaN);
    riskHeatmapIndicator.addPoint(Double.NaN);
    refugeHeatmapIndicator.addPoint(Double.NaN);
    megaHeatmapIndicator.addPoint(Double.NaN);
    ndxIndicator.addPoint(Double.NaN);
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
    for (int i = 0; i < MEGA_COUNT; i++) {
      double perf = calcStockPerf(megaOpen[i], currentMegaPrices[i]);
      double weight = settings.megaWeights[i];
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

    for (int j = 0; j < STOCK_COUNT; j++) {
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
      for (int j = 0; j < STOCK_COUNT; j++) {
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
    double[][] matrix = new double[totalRows][STOCK_COUNT];
    int r = 0;
    for (TimedReturnRow timedRow : completedReturnRows) {
      matrix[r++] = timedRow.returns;
    }
    if (extraCurrentRow != null) {
      matrix[r] = extraCurrentRow;
    }
    return calcBasketCorrelations(matrix, RISK_COUNT);
  }

  private static double[] buildNormalizedReturnRow(
      double[] prevPrices, double[] currPrices, long dtSec) {
    double scale = 1.0 / Math.sqrt(Math.max(1L, dtSec));
    double[] row = new double[STOCK_COUNT];
    for (int j = 0; j < STOCK_COUNT; j++) {
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

  private static boolean isWindowRowValid(double[] row) {
    int validRisk = 0;
    int validRef = 0;
    for (int j = 0; j < RISK_COUNT; j++) {
      if (!Double.isNaN(row[j])) {
        validRisk++;
      }
    }
    for (int j = RISK_COUNT; j < STOCK_COUNT; j++) {
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
  private static double[] calcBasketCorrelations(double[][] retMatrix, int splitIndex) {
    int rowCount = retMatrix.length;
    if (rowCount < MIN_CORR_STEPS) {
      return new double[] {Double.NaN, Double.NaN};
    }
    double[] zSumsA = new double[rowCount];
    double[] zSumsB = new double[rowCount];
    int countA = 0;
    int countB = 0;

    for (int j = 0; j < STOCK_COUNT; j++) {
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

  private synchronized void resetSessionData() {
    ndxOpen = Double.NaN;
    ndxLast = Double.NaN;
    Arrays.fill(stockOpen, Double.NaN);
    Arrays.fill(stockLast, Double.NaN);
    Arrays.fill(megaOpen, Double.NaN);
    Arrays.fill(megaLast, Double.NaN);
    Arrays.fill(stepStartPrices, Double.NaN);
    histSessionDate = null;
    histMinuteCloses.clear();
    Arrays.fill(histFirstBarSec, Long.MAX_VALUE);
    Arrays.fill(histLastBarSec, Long.MIN_VALUE);
    Arrays.fill(histOpen0930, Double.NaN);
    Arrays.fill(histBarCount, 0);
    Arrays.fill(histTickerDone, false);
    histCompletedTickers = 0;
    histCompletedStocks = 0;
    lastTwsError = "None";
    completedReturnRows.clear();
    lastCorrStepEpochSec = -1;
    wasRiskBelowChart = false;
    currentRegime = Regime.NEUTRAL;
  }

  private void onNewRegularSessionOpen() {
    resetSessionData();
    if (isRealtime) {
      twsBridge.resubscribeIfSubscribed();
    }
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
    for (int i = 0; i < STOCK_COUNT; i++) {
      double hOpen = histOpen0930[i];
      if (!Double.isNaN(hOpen) && hOpen > 0.0) {
        stockOpen[i] = hOpen;
      }
    }
    for (int i = 0; i < MEGA_COUNT; i++) {
      double hOpen = histOpen0930[STOCK_COUNT + i];
      if (!Double.isNaN(hOpen) && hOpen > 0.0) {
        megaOpen[i] = hOpen;
      }
    }
    double hNdxOpen = histOpen0930[SLOT_NDX];
    if (!Double.isNaN(hNdxOpen) && hNdxOpen > 0.0) {
      ndxOpen = hNdxOpen;
    }

    long histRthStartSec =
        histSessionDate.atTime(RTH_OPEN).atZone(NY_ZONE).toInstant().getEpochSecond();
    LocalDate targetChartDate = resolveChartRthDate();
    long chartRthStartSec =
        targetChartDate.atTime(RTH_OPEN).atZone(NY_ZONE).toInstant().getEpochSecond();
    long chartRthStartNanos = chartRthStartSec * 1_000_000_000L;

    if (!nqOpen0930Locked) {
      Map.Entry<Long, Double> firstNq = nqMinuteCloses.ceilingEntry(chartRthStartSec);
      if (firstNq != null && firstNq.getKey() <= chartRthStartSec + 60L) {
        nqOpenLevel0930 = firstNq.getValue();
        nqOpen0930Locked = true;
      } else {
        double currentNdxPerf = calcStockPerf(ndxOpen, ndxLast);
        double currentCompPerf =
            calcCompositeStockPerf(
                calcBasketPerf(0, RISK_COUNT),
                calcBasketPerf(RISK_COUNT, STOCK_COUNT),
                calcMegaPerf());
        ensureNqOpen0930(!Double.isNaN(currentNdxPerf) ? currentNdxPerf : currentCompPerf);
      }
    }

    // 2. Vide intégralement les indicateurs avant d'insérer par ordre chronologique strict
    clearIndicators(0L, Long.MAX_VALUE / 2);

    // 3. Réinitialise l'état de séance et rejoue chaque minute depuis 09h30 NY
    wasRiskBelowChart = false;
    currentRegime = Regime.NEUTRAL;
    completedReturnRows.clear();

    double[] runningStock = stockOpen.clone();
    double[] prevStepStock = stockOpen.clone();
    double[] runningMega = megaOpen.clone();
    double runningNdx = ndxOpen;
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

      for (int i = 0; i < STOCK_COUNT; i++) {
        double c = closes[i];
        if (!Double.isNaN(c) && c > 0.0) {
          runningStock[i] = c;
        }
      }
      for (int i = 0; i < MEGA_COUNT; i++) {
        double c = closes[STOCK_COUNT + i];
        if (!Double.isNaN(c) && c > 0.0) {
          runningMega[i] = c;
        }
      }
      double cNdx = closes[SLOT_NDX];
      if (!Double.isNaN(cNdx) && cNdx > 0.0) {
        runningNdx = cNdx;
      }

      double hRiskPerf = calcBasketPerfFromArray(runningStock, 0, RISK_COUNT);
      double hRefPerf = calcBasketPerfFromArray(runningStock, RISK_COUNT, STOCK_COUNT);
      double hMegaPerf = calcMegaPerfFromArray(runningMega);
      double hCompPerf = calcCompositeStockPerf(hRiskPerf, hRefPerf, hMegaPerf);
      double hNdxPerf = calcStockPerf(ndxOpen, runningNdx);
      double hRefPerfForNq = !Double.isNaN(hNdxPerf) ? hNdxPerf : hCompPerf;

      Map.Entry<Long, Double> nqEntry = nqMinuteCloses.floorEntry(barEndSec);
      boolean hasNqBar = nqEntry != null && Math.abs(nqEntry.getKey() - barEndSec) <= 120L;
      if (!nqOpen0930Locked && hasNqBar && !Double.isNaN(hRefPerfForNq)) {
        nqOpenLevel0930 = nqEntry.getValue() / (1.0 + hRefPerfForNq / 100.0);
        nqOpen0930Locked = true;
      }

      double hChartPerf = !Double.isNaN(hNdxPerf) ? hNdxPerf : hCompPerf;
      double hSpread =
          (!Double.isNaN(hRefPerf) && !Double.isNaN(hRiskPerf)) ? hRefPerf - hRiskPerf : Double.NaN;

      double[] retRow = buildNormalizedReturnRow(prevStepStock, runningStock, 60L);
      for (int j = 0; j < STOCK_COUNT; j++) {
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
          hNdxPerf,
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
    for (int j = 0; j < STOCK_COUNT; j++) {
      stepStartPrices[j] =
          (!Double.isNaN(prevStepStock[j]) && prevStepStock[j] > 0.0)
              ? prevStepStock[j]
              : stockLast[j];
    }
    lastCorrStepEpochSec = lastBarEndSec;

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
    ndxHeatmapIndicator.clear(fromNanos, toNanos);
    riskHeatmapIndicator.clear(fromNanos, toNanos);
    refugeHeatmapIndicator.clear(fromNanos, toNanos);
    megaHeatmapIndicator.clear(fromNanos, toNanos);
    ndxIndicator.clear(fromNanos, toNanos);
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
      double ndxPerf,
      double riskPerf,
      double refPerf,
      double megaPerf,
      double spread,
      double crossCorr,
      double withinCorr,
      Regime regime) {
    addPointIfValid(ndxHeatmapIndicator, timestampNanos, projectFromOpen0930(ndxPerf));
    addPointIfValid(riskHeatmapIndicator, timestampNanos, projectFromOpen0930(riskPerf));
    addPointIfValid(refugeHeatmapIndicator, timestampNanos, projectFromOpen0930(refPerf));
    addPointIfValid(megaHeatmapIndicator, timestampNanos, projectFromOpen0930(megaPerf));

    addPointIfValid(ndxIndicator, timestampNanos, ndxPerf);
    addPointIfValid(riskIndicator, timestampNanos, riskPerf);
    addPointIfValid(refugeIndicator, timestampNanos, refPerf);
    addPointIfValid(megaIndicator, timestampNanos, megaPerf);

    regimeIndicator.addPoint(timestampNanos, regime.score);
    addPointIfValid(spreadIndicator, timestampNanos, spread);
    addPointIfValid(crossIndicator, timestampNanos, crossCorr);
    addPointIfValid(withinIndicator, timestampNanos, withinCorr);
  }

  private static void addPointIfValid(
      IndicatorModifiable indicator, long timestampNanos, double value) {
    if (!Double.isNaN(value)) {
      indicator.addPoint(timestampNanos, value);
    }
  }

  private static int slotForTickerId(int tickerId) {
    if (tickerId == TICKER_NDX) {
      return SLOT_NDX;
    }
    if (tickerId >= TICKER_RISK_BASE && tickerId < TICKER_RISK_BASE + RISK_COUNT) {
      return tickerId - TICKER_RISK_BASE;
    }
    if (tickerId >= TICKER_REF_BASE && tickerId < TICKER_REF_BASE + REFUGE_COUNT) {
      return RISK_COUNT + (tickerId - TICKER_REF_BASE);
    }
    if (tickerId >= TICKER_MEGA_BASE && tickerId < TICKER_MEGA_BASE + MEGA_COUNT) {
      return STOCK_COUNT + (tickerId - TICKER_MEGA_BASE);
    }
    return -1;
  }

  private static double[] emptySlotArray() {
    double[] arr = new double[TOTAL_SLOTS];
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
    Color ndxCol = new Color(settings.ndxLineColor);
    Color riskCol = new Color(settings.riskLineColor);
    Color refCol = new Color(settings.refugeLineColor);
    Color megaCol = new Color(settings.megaLineColor);

    ndxHeatmapIndicator.setColor(ndxCol);
    riskHeatmapIndicator.setColor(riskCol);
    refugeHeatmapIndicator.setColor(refCol);
    megaHeatmapIndicator.setColor(megaCol);

    ndxIndicator.setColor(ndxCol);
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
            if (subscribed) {
              cancelAllSubscriptions();
            }
            client.eDisconnect();
          }
        } catch (Exception ignored) {
          // Fermeture silencieuse
        }
      }
      subscribed = false;
      if (signal != null) {
        signal.issueSignal();
      }
      updateStatus("Disconnected");
    }

    synchronized void subscribe() {
      if (client == null || !client.isConnected()) {
        updateStatus("Not connected — click Connect first");
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

    synchronized void resubscribeIfSubscribed() {
      if (!subscribed || client == null || !client.isConnected()) {
        return;
      }
      cancelAllSubscriptions();
      resetSessionData();
      subscribeAllContracts();
    }

    private void cancelAllSubscriptions() {
      if (client == null || !client.isConnected()) {
        return;
      }
      try {
        cancelTickerAndHistory(TICKER_NDX);
        for (int i = 0; i < RISK_COUNT; i++) {
          cancelTickerAndHistory(TICKER_RISK_BASE + i);
        }
        for (int i = 0; i < REFUGE_COUNT; i++) {
          cancelTickerAndHistory(TICKER_REF_BASE + i);
        }
        for (int i = 0; i < MEGA_COUNT; i++) {
          cancelTickerAndHistory(TICKER_MEGA_BASE + i);
        }
      } catch (Exception ignored) {
        // Ignoré si déjà déconnecté
      }
    }

    private void cancelTickerAndHistory(int tickerId) {
      client.cancelMktData(tickerId);
      client.cancelHistoricalData(tickerId + HIST_REQ_OFFSET);
    }

    private void subscribeAllContracts() {
      if (client == null || !client.isConnected()) {
        return;
      }
      final int gen = ++subGeneration;
      final EClientSocket sock = client;

      // Bascule automatique sur Delayed-Frozen si un flux temps réel manque, temps réel sinon
      sock.reqMarketDataType(4);

      final int[] tickerIds = new int[TOTAL_SLOTS];
      final Contract[] contracts = new Contract[TOTAL_SLOTS];
      int k = 0;
      for (int i = 0; i < RISK_COUNT; i++) {
        tickerIds[k] = TICKER_RISK_BASE + i;
        contracts[k] = buildUsStockContract(settings.riskSymbols[i]);
        k++;
      }
      for (int i = 0; i < REFUGE_COUNT; i++) {
        tickerIds[k] = TICKER_REF_BASE + i;
        contracts[k] = buildUsStockContract(settings.refugeSymbols[i]);
        k++;
      }
      for (int i = 0; i < MEGA_COUNT; i++) {
        tickerIds[k] = TICKER_MEGA_BASE + i;
        contracts[k] = buildUsStockContract(settings.megaSymbols[i]);
        k++;
      }
      tickerIds[SLOT_NDX] = TICKER_NDX;
      contracts[SLOT_NDX] = buildNdxContract();

      // 1. Streaming temps réel immédiat pour les 21 actions US + l'indice NDX
      for (int i = 0; i < TOTAL_SLOTS; i++) {
        if (contracts[i] != null) {
          sock.reqMktData(tickerIds[i], contracts[i], "", false, false, null);
        } else {
          markHistoricalSlotDone(i);
        }
      }

      updateStatus(
          "Subscribed · Loading RTH history ("
              + histCompletedTickers
              + "/"
              + TOTAL_SLOTS
              + ")...");

      // 2. Requêtes historiques RTH 1m espacées de 60 ms (NDX en premier, puis les 21 actions)
      Thread histThread =
          new Thread(
              () -> {
                for (int step = 0; step < TOTAL_SLOTS; step++) {
                  int i = (step == 0) ? SLOT_NDX : (step - 1);
                  if (!active || !subscribed || subGeneration != gen || !sock.isConnected()) {
                    return;
                  }
                  if (contracts[i] != null) {
                    try {
                      sock.reqHistoricalData(
                          tickerIds[i] + HIST_REQ_OFFSET,
                          contracts[i],
                          "",
                          "1 D",
                          "1 min",
                          "TRADES",
                          1,
                          2,
                          false,
                          null);
                      Thread.sleep(60L);
                    } catch (InterruptedException ie) {
                      Thread.currentThread().interrupt();
                      return;
                    } catch (Exception e) {
                      markHistoricalSlotDone(i);
                    }
                  }
                }
              },
              "MarketRegime-Hist-Requester");
      histThread.setDaemon(true);
      histThread.start();
    }

    private Contract buildNdxContract() {
      Contract c = new Contract();
      c.symbol("NDX");
      c.secType("IND");
      c.exchange("NASDAQ");
      c.currency("USD");
      return c;
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
      boolean isLastField =
          (field == FIELD_LAST || field == FIELD_LAST_RTH || field == FIELD_DELAYED_LAST);
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
        if (tickerId >= TICKER_RISK_BASE && tickerId < TICKER_RISK_BASE + RISK_COUNT) {
          int idx = tickerId - TICKER_RISK_BASE;
          updateStockSlot(idx, idx, isOpenField, inOpeningMinute, price);
        } else if (tickerId >= TICKER_REF_BASE && tickerId < TICKER_REF_BASE + REFUGE_COUNT) {
          int idx = RISK_COUNT + (tickerId - TICKER_REF_BASE);
          updateStockSlot(idx, idx, isOpenField, inOpeningMinute, price);
        } else if (tickerId >= TICKER_MEGA_BASE && tickerId < TICKER_MEGA_BASE + MEGA_COUNT) {
          int idx = tickerId - TICKER_MEGA_BASE;
          int histSlot = STOCK_COUNT + idx;
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
        } else if (tickerId == TICKER_NDX) {
          if (isOpenField) {
            if (Double.isNaN(histOpen0930[SLOT_NDX])) {
              ndxOpen = price;
            }
          } else {
            ndxLast = price;
            if (inOpeningMinute && Double.isNaN(ndxOpen)) {
              ndxOpen = price;
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
      int slot = slotForTickerId(reqId - HIST_REQ_OFFSET);
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
      int slot = slotForTickerId(reqId - HIST_REQ_OFFSET);
      if (slot >= 0) {
        markHistoricalSlotDone(slot);
      }
    }

    private void applySlotOpen(int slot, double openPrice) {
      if (slot == SLOT_NDX) {
        ndxOpen = openPrice;
      } else if (slot < STOCK_COUNT) {
        stockOpen[slot] = openPrice;
      } else {
        megaOpen[slot - STOCK_COUNT] = openPrice;
      }
    }

    private void applySlotLastIfMissing(int slot, double closePrice) {
      if (slot == SLOT_NDX) {
        if (Double.isNaN(ndxLast)) {
          ndxLast = closePrice;
        }
      } else if (slot < STOCK_COUNT) {
        if (Double.isNaN(stockLast[slot])) {
          stockLast[slot] = closePrice;
        }
      } else {
        int idx = slot - STOCK_COUNT;
        if (Double.isNaN(megaLast[idx])) {
          megaLast[idx] = closePrice;
        }
      }
    }

    private void markHistoricalSlotDone(int slot) {
      if (slot < 0 || slot >= TOTAL_SLOTS) {
        return;
      }
      boolean shouldRebuild = false;
      int doneCount;
      int stockDoneCount;
      synchronized (MarketRegime.this) {
        if (!histTickerDone[slot]) {
          histTickerDone[slot] = true;
          histCompletedTickers++;
          if (slot < STOCK_SLOTS) {
            histCompletedStocks++;
          }
          shouldRebuild =
              (histCompletedTickers == TOTAL_SLOTS
                  || histCompletedStocks == STOCK_SLOTS
                  || histCompletedTickers % 5 == 0);
        }
        doneCount = histCompletedTickers;
        stockDoneCount = histCompletedStocks;
      }
      if (shouldRebuild) {
        rebuildHistoryFrom0930();
      }
      if (subscribed) {
        if (doneCount >= TOTAL_SLOTS || stockDoneCount >= STOCK_SLOTS) {
          updateStatus(
              "Subscribed ("
                  + settings.twsHost
                  + ":"
                  + settings.twsPort
                  + ") · NDX + 21 stocks (RTH history ready)");
        } else {
          updateStatus(
              "Subscribed · Loading RTH history (" + doneCount + "/" + TOTAL_SLOTS + ")...");
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
      int histSlot = slotForTickerId(id - HIST_REQ_OFFSET);
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
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim().toUpperCase();
    int colon = trimmed.indexOf(':');
    return colon >= 0 ? trimmed.substring(colon + 1).trim() : trimmed;
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
            new JLabel("Heatmap scale"),
            doubleSpinner(
                settings.heatmapScale,
                0.05,
                2.00,
                0.05,
                v -> {
                  settings.heatmapScale = v;
                  rebuildHistoryFrom0930();
                })));

    // 3. Section Couleurs des Courbes
    JPanel lineColors = verticalBox();
    lineColors.add(
        colorRow(
            new Color(settings.ndxLineColor),
            DEFAULT_NDX_COLOR,
            "NDX index (%)",
            c -> {
              settings.ndxLineColor = c.getRGB();
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

    // 5. Section Risk Basket (9 actions)
    JPanel riskBasketPanel = verticalBox();
    for (int i = 0; i < RISK_COUNT; i++) {
      final int idx = i;
      if (i > 0) {
        riskBasketPanel.add(gap());
      }
      riskBasketPanel.add(
          pair(
              new JLabel("Risk " + (i + 1)),
              textField(
                  settings.riskSymbols[idx], 10, v -> settings.riskSymbols[idx] = cleanSymbol(v))));
    }
    riskBasketPanel.add(gap());
    JButton applyRiskBtn = new JButton("Apply Risk basket");
    applyRiskBtn.addActionListener(
        e -> {
          saveSettings();
          twsBridge.resubscribeIfSubscribed();
        });
    riskBasketPanel.add(pair(new JLabel(""), applyRiskBtn));

    // 6. Section Refuge Basket (7 actions)
    JPanel refugeBasketPanel = verticalBox();
    for (int i = 0; i < REFUGE_COUNT; i++) {
      final int idx = i;
      if (i > 0) {
        refugeBasketPanel.add(gap());
      }
      refugeBasketPanel.add(
          pair(
              new JLabel("Refuge " + (i + 1)),
              textField(
                  settings.refugeSymbols[idx],
                  10,
                  v -> settings.refugeSymbols[idx] = cleanSymbol(v))));
    }
    refugeBasketPanel.add(gap());
    JButton applyRefugeBtn = new JButton("Apply Refuge basket");
    applyRefugeBtn.addActionListener(
        e -> {
          saveSettings();
          twsBridge.resubscribeIfSubscribed();
        });
    refugeBasketPanel.add(pair(new JLabel(""), applyRefugeBtn));

    // 7. Section Mega-cap Basket (5 actions + poids)
    JPanel megaBasketPanel = verticalBox();
    for (int i = 0; i < MEGA_COUNT; i++) {
      final int idx = i;
      if (i > 0) {
        megaBasketPanel.add(gap());
      }
      JPanel inlineControls = new JPanel(new GridBagLayout());
      inlineControls.setOpaque(false);
      GridBagConstraints gc = new GridBagConstraints();
      gc.gridx = 0;
      gc.gridy = 0;
      gc.insets = new Insets(0, 0, 0, 6);
      inlineControls.add(
          textField(settings.megaSymbols[idx], 5, v -> settings.megaSymbols[idx] = cleanSymbol(v)),
          gc);
      gc = new GridBagConstraints();
      gc.gridx = 1;
      gc.gridy = 0;
      inlineControls.add(
          doubleSpinner(
              settings.megaWeights[idx], 0.0, 50.0, 0.01, v -> settings.megaWeights[idx] = v),
          gc);
      megaBasketPanel.add(pair(new JLabel("Mega " + (i + 1) + " (sym / weight)"), inlineControls));
    }
    megaBasketPanel.add(gap());
    JButton applyMegaBtn = new JButton("Apply Mega-cap basket");
    applyMegaBtn.addActionListener(
        e -> {
          saveSettings();
          twsBridge.resubscribeIfSubscribed();
        });
    megaBasketPanel.add(pair(new JLabel(""), applyMegaBtn));

    JPanel about = verticalBox();
    about.add(new FittingLabel("Market Regime " + VERSION, false));

    return new StrategyPanel[] {
      section("TWS Connection", twsPanel, 6, TEXT_INDENT),
      section("Regime & Correlations", regimeParams, 6, TEXT_INDENT),
      section("Line Colors", lineColors, 4, 0),
      section("Regime Colors", regimeColors, 4, 0),
      section("Risk Basket (9 stocks)", riskBasketPanel, 6, TEXT_INDENT),
      section("Refuge Basket (7 stocks)", refugeBasketPanel, 6, TEXT_INDENT),
      section("Mega-cap Basket (5 stocks)", megaBasketPanel, 6, TEXT_INDENT),
      section("About", about, 4, TEXT_INDENT)
    };
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
    if (s.riskSymbols == null || s.riskSymbols.length != RISK_COUNT) {
      s.riskSymbols = DEFAULT_RISK_SYMBOLS.clone();
    }
    if (s.refugeSymbols == null || s.refugeSymbols.length != REFUGE_COUNT) {
      s.refugeSymbols = DEFAULT_REFUGE_SYMBOLS.clone();
    }
    if (s.megaSymbols == null || s.megaSymbols.length != MEGA_COUNT) {
      s.megaSymbols = DEFAULT_MEGA_SYMBOLS.clone();
    }
    if (s.megaWeights == null || s.megaWeights.length != MEGA_COUNT) {
      s.megaWeights = DEFAULT_MEGA_WEIGHTS.clone();
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
    if (s.ndxLineColor == 0) {
      s.ndxLineColor = DEFAULT_NDX_COLOR.getRGB();
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

    double riskPerf = calcBasketPerf(0, RISK_COUNT);
    double refPerf = calcBasketPerf(RISK_COUNT, STOCK_COUNT);
    double megaPerf = calcMegaPerf();
    double compPerf = calcCompositeStockPerf(riskPerf, refPerf, megaPerf);
    double ndxPerf = calcStockPerf(ndxOpen, ndxLast);
    double mnqPerf =
        (nqOpen0930Locked && !Double.isNaN(lastNqLevel) && nqOpenLevel0930 > 0.0)
            ? (lastNqLevel / nqOpenLevel0930 - 1.0) * 100.0
            : compPerf;
    double chartPerf = !Double.isNaN(ndxPerf) ? ndxPerf : mnqPerf;
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
        .append(histCompletedTickers)
        .append('/')
        .append(TOTAL_SLOTS)
        .append(" instruments · ")
        .append(histMinuteCloses.size())
        .append(" TWS bars (1m) · ")
        .append(nqMinuteCloses.size())
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
        .append("m)\n\n");

    sb.append("SUMMARY (% since 09:30 NY)\n");
    sb.append("------------------------------------------------------------\n");
    sb.append(String.format(Locale.US, "NDX Index    : %s%n", fmtPct(ndxPerf)));
    sb.append(String.format(Locale.US, "Chart (MNQ)  : %s%n", fmtPct(mnqPerf)));
    sb.append(String.format(Locale.US, "Active Bench : %s%n", fmtPct(chartPerf)));
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
        "INDEX",
        "NDX",
        ndxOpen,
        ndxLast,
        histBarCount[SLOT_NDX] + "m");
    appendTickerRow(
        sb,
        "CHART",
        "MNQ/NQ",
        nqOpenLevel0930 * instrumentPips,
        lastNqLevel * instrumentPips,
        nqMinuteCloses.size() + "m");
    for (int i = 0; i < RISK_COUNT; i++) {
      appendTickerRow(
          sb,
          "RISK " + (i + 1),
          settings.riskSymbols[i],
          stockOpen[i],
          stockLast[i],
          histBarCount[i] + "m");
    }
    for (int i = 0; i < REFUGE_COUNT; i++) {
      int slot = RISK_COUNT + i;
      appendTickerRow(
          sb,
          "REFUGE " + (i + 1),
          settings.refugeSymbols[i],
          stockOpen[slot],
          stockLast[slot],
          histBarCount[slot] + "m");
    }
    for (int i = 0; i < MEGA_COUNT; i++) {
      int histSlot = STOCK_COUNT + i;
      String extra =
          String.format(Locale.US, "w=%.2f %dm", settings.megaWeights[i], histBarCount[histSlot]);
      appendTickerRow(
          sb, "MEGA " + (i + 1), settings.megaSymbols[i], megaOpen[i], megaLast[i], extra);
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
    return "1.0.0";
  }
}
