package com.marketregime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Watchlist CSV des paniers Risk, Refuge et Mega-cap, exportée par Quant Research.
 *
 * <p>Une ligne par action : {@code basket,symbol,weight}. La colonne {@code basket} vaut {@code
 * risk}, {@code refuge} ou {@code mega} ; {@code weight} ne sert qu'aux méga-caps. L'en-tête est
 * facultatif, le séparateur peut être {@code ,} ou {@code ;} (Excel en français), les lignes vides
 * ou commençant par {@code #} sont ignorées et un préfixe de place ({@code NASDAQ:ALAB}) est accepté.
 * Les trois paniers sont obligatoires : la watchlist est la seule source des paniers de l'add-on.
 */
final class Watchlist {

  static final int MIN_BASKET_STOCKS = 5;
  static final int MIN_MEGA_STOCKS = 3;

  final String[] riskSymbols;
  final String[] refugeSymbols;

  final String[] megaSymbols;

  /** Poids Mega-cap, même ordre que {@link #megaSymbols}. */
  final double[] megaWeights;

  /** Remarques non bloquantes (doublons, paniers tronqués, lignes ignorées). */
  final List<String> warnings;

  private Watchlist(
      String[] riskSymbols,
      String[] refugeSymbols,
      String[] megaSymbols,
      double[] megaWeights,
      List<String> warnings) {
    this.riskSymbols = riskSymbols;
    this.refugeSymbols = refugeSymbols;
    this.megaSymbols = megaSymbols;
    this.megaWeights = megaWeights;
    this.warnings = warnings;
  }

  /** Erreur de lecture ou de contenu, avec un message prêt à afficher. */
  static final class WatchlistException extends Exception {
    WatchlistException(String message) {
      super(message);
    }
  }

  static Watchlist read(Path path, int maxRisk, int maxRefuge, int maxMega)
      throws WatchlistException {
    List<String> lines;
    try {
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new WatchlistException("Cannot read " + path + " (" + e.getMessage() + ")");
    }
    return parse(lines, maxRisk, maxRefuge, maxMega);
  }

  static Watchlist parse(List<String> lines, int maxRisk, int maxRefuge, int maxMega)
      throws WatchlistException {
    List<String> warnings = new ArrayList<>();
    Map<String, Double> risk = new LinkedHashMap<>();
    Map<String, Double> refuge = new LinkedHashMap<>();
    Map<String, Double> mega = new LinkedHashMap<>();

    for (int n = 0; n < lines.size(); n++) {
      String line = lines.get(n);
      if (n == 0 && line.startsWith("﻿")) {
        line = line.substring(1);
      }
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] cells = line.split(line.indexOf(';') >= 0 ? ";" : ",", -1);
      String basket = unquote(cells[0]).toLowerCase(Locale.ROOT);
      if (basket.equals("basket")) {
        continue;
      }
      Map<String, Double> target =
          switch (basket) {
            case "risk", "risque" -> risk;
            case "refuge", "safe" -> refuge;
            case "mega", "mega-cap", "megacap", "mega-caps" -> mega;
            default -> null;
          };
      if (target == null) {
        warnings.add("Line " + (n + 1) + " ignored (unknown basket \"" + basket + "\")");
        continue;
      }
      String symbol = cells.length > 1 ? cleanSymbol(unquote(cells[1])) : "";
      if (symbol.isEmpty()) {
        warnings.add("Line " + (n + 1) + " ignored (no symbol)");
        continue;
      }
      double weight = Double.NaN;
      if (cells.length > 2 && !unquote(cells[2]).isEmpty()) {
        try {
          weight = Double.parseDouble(unquote(cells[2]).replace(',', '.'));
        } catch (NumberFormatException e) {
          warnings.add("Line " + (n + 1) + ": weight \"" + cells[2].trim() + "\" ignored");
        }
      }
      if (target.containsKey(symbol)) {
        warnings.add(symbol + " listed twice in " + basket + ", kept once");
        continue;
      }
      target.put(symbol, weight);
    }

    String[] riskSymbols = truncate("Risk", risk, maxRisk, warnings);
    String[] refugeSymbols = truncate("Refuge", refuge, maxRefuge, warnings);
    if (riskSymbols.length < MIN_BASKET_STOCKS || refugeSymbols.length < MIN_BASKET_STOCKS) {
      throw new WatchlistException(
          "Need at least "
              + MIN_BASKET_STOCKS
              + " Risk and "
              + MIN_BASKET_STOCKS
              + " Refuge stocks (found "
              + riskSymbols.length
              + " Risk, "
              + refugeSymbols.length
              + " Refuge)");
    }
    for (String sym : riskSymbols) {
      if (refuge.containsKey(sym)) {
        warnings.add(sym + " is in both Risk and Refuge");
      }
    }

    String[] megaSymbols = truncate("Mega-cap", mega, maxMega, warnings);
    if (megaSymbols.length < MIN_MEGA_STOCKS) {
      throw new WatchlistException(
          "Need at least " + MIN_MEGA_STOCKS + " Mega-cap stocks (found " + megaSymbols.length + ")");
    }
    double[] megaWeights = new double[megaSymbols.length];
    for (int i = 0; i < megaSymbols.length; i++) {
      double w = mega.get(megaSymbols[i]);
      if (Double.isNaN(w) || w <= 0.0) {
        throw new WatchlistException("Mega-cap " + megaSymbols[i] + " needs a positive weight");
      }
      megaWeights[i] = w;
    }
    return new Watchlist(riskSymbols, refugeSymbols, megaSymbols, megaWeights, warnings);
  }

  private static String[] truncate(
      String name, Map<String, Double> basket, int max, List<String> warnings) {
    List<String> symbols = new ArrayList<>(basket.keySet());
    if (symbols.size() > max) {
      warnings.add(name + ": " + symbols.size() + " stocks, only the first " + max + " are kept");
      symbols = symbols.subList(0, max);
    }
    return symbols.toArray(new String[0]);
  }

  private static String unquote(String cell) {
    String s = cell.trim();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1).trim();
    }
    return s;
  }

  /** Nettoie un symbole (ex. "nasdaq:nvda" -> "NVDA"). */
  static String cleanSymbol(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim().toUpperCase(Locale.ROOT);
    int colon = trimmed.indexOf(':');
    return colon >= 0 ? trimmed.substring(colon + 1).trim() : trimmed;
  }
}
