# Market Regime - Bookmap Add-on (TWS API)

Add-on Bookmap L1 (`@Layer1SimpleAttachable`) qui se connecte à **Interactive Brokers TWS / IB Gateway** en streaming (`reqMktData`) et en historique 1 minute (`reqHistoricalData`) pour calculer et afficher le **Market Regime** intraday (session régulière US **09h30 – 16h00 America/New_York**), même si Bookmap est ouvert en cours de séance.

## Affichage dans Bookmap

### 1. Sur la Heatmap (`GraphType.PRIMARY`)
Les **4 courbes** de performance depuis l'ouverture RTH (`09h30 NY`) sont projetées de façon continue sur l'échelle de prix du contrat `MNQ / NQ` (avec facteur d'échelle vertical ajustable via **`Heatmap scale`**, `0.25` par défaut) :
- **`NDX (Heatmap)`** (`#FFEB3B`) — Indice NASDAQ-100 en direct et historique 1m depuis TWS API (référence principale du régime)
- **`Risk (Heatmap)`** (`#2962FF`) — Panier de 9 actions NASDAQ équipondérées (`ALAB`, `ARM`, `CRWV`, `KLAC`, `LRCX`, `MRVL`, `NBIS`, `SNDK`, `WDC`)
- **`Refuge (Heatmap)`** (`#FF9800`) — Panier de 7 actions NASDAQ équipondérées (`ADBE`, `ADP`, `CTAS`, `INTU`, `ROP`, `TRI`, `WDAY`)
- **`Mega-cap (Heatmap)`** (`#9C27B0`) — Panier de 5 actions NASDAQ pondérées par leur capitalisation (`NVDA`, `AAPL`, `MSFT`, `GOOGL`, `META`)

### 2. Dans le Bottom Chart et le Widgets Panel (`GraphType.BOTTOM`)
Tous les widgets utilisent `WidgetDisplayInfo.Type.SYMMETRIC` centré sur `0` :
- **`NDX`**, **`Risk`**, **`Refuge`**, **`Mega-cap`** — Variations directes en `%` depuis `09h30 NY` (partageant le même `AxisGroup`)
- **`Market Regime`** (`-2` à `+2`, **couleur dynamique en temps réel** selon le régime actif) :
  - `+2` : **Rotation to risk** (`#2962FF`)
  - `+1` : **Slow long** (`#089981`)
  - `0` : **Neutral** (`#787B86`)
  - `-1` : **Rotation to refuge** (`#FF9800`)
  - `-2` : **Liquidation** (`#F23645`)
- **`Refuge - Risk`** (`#DBDBDB`) — Écart de performance en `%` entre le panier Refuge et le panier Risk
- **`Cross`** (`#26C6DA`) — Corrélation moyenne inter-paniers (Risk vs Refuge) sur fenêtre glissante (20 min par défaut)
- **`Within`** (`#AB47BC`) — Corrélation moyenne intra-paniers sur fenêtre glissante

### 3. Diagnostic intégré (`Data Snapshot`)
Un bouton **`Data Snapshot`** dans les réglages de l'add-on affiche l'état complet de la connexion TWS, des barres historiques 1 minute reçues depuis `09h30 NY`, des prix d'ouverture `09h30`, des derniers prix et des performances en `%` de chaque instrument (`NDX` + 21 actions).

## Compilation

```powershell
.\gradlew.bat clean jar
```

Produit :
- `BrunoF_MarketRegime_1.0.0.jar` (JAR autonome incluant `TwsApi.jar` et `protobuf-java`, conforme au [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html))
