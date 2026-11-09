# Market Regime - Bookmap Add-on (TWS API)

Add-on Bookmap L1 (`@Layer1SimpleAttachable`) qui se connecte à **Interactive Brokers TWS / IB Gateway** en streaming (`reqMktData`) et en historique 1 minute (`reqHistoricalData`) pour calculer et afficher le **Market Regime** intraday (session régulière US **09h30 – 16h00 America/New_York**), même si Bookmap est ouvert en cours de séance.

La **référence du régime est l'instrument affiché sur la Heatmap** (NQ, MNQ, ES, une action...) : sa performance depuis 09h30 NY est calculée à partir de ses propres trades dans Bookmap. Aucun indice n'est demandé à TWS ; seules les actions des paniers le sont.

## Affichage dans Bookmap

### 1. Sur la Heatmap (`GraphType.PRIMARY`)
Les **3 courbes** de performance des paniers depuis l'ouverture RTH (`09h30 NY`) sont ancrées sur le prix 09h30 de l'instrument du graphique (avec facteur d'échelle vertical ajustable via **`Heatmap scale`**, `0.25` par défaut) :
- **`Risk (Heatmap)`** (`#2962FF`) — Panier Risk, de 5 à 30 actions NASDAQ équipondérées
- **`Refuge (Heatmap)`** (`#FF9800`) — Panier Refuge, de 5 à 29 actions NASDAQ équipondérées
- **`Mega-cap (Heatmap)`** (`#9C27B0`) — Panier Mega-cap, de 3 à 5 actions NASDAQ pondérées par leur capitalisation

Les trois paniers viennent de la watchlist CSV (voir plus bas).

**`Heatmap auto scale`** (activé par défaut) ramène chaque courbe à l'amplitude de l'instrument du graphique : son échelle est le rapport entre l'écart-type des variations 1 minute de l'instrument et celui du panier depuis 09h30 (borné entre `0.05` et `2.00`). Les courbes restent ainsi dans la zone du prix quel que soit le symbole. Pendant les 10 premières minutes, faute de données, c'est l'échelle manuelle qui s'applique. En mode Auto, la position d'une courbe par rapport au prix montre une performance ajustée à la volatilité ; le régime, lui, utilise toujours les performances brutes du Bottom Chart.

Si Bookmap n'a aucun trade de l'instrument à 09h30 (graphique ouvert en cours de séance sans historique), l'ouverture est estimée à partir du composite des paniers ; le `Data Snapshot` l'indique.

### 2. Dans le Bottom Chart et le Widgets Panel (`GraphType.BOTTOM`)
Tous les widgets utilisent `WidgetDisplayInfo.Type.SYMMETRIC` centré sur `0` :
- **`Chart`** (`#FFEB3B`), **`Risk`**, **`Refuge`**, **`Mega-cap`** — Variations directes en `%` depuis `09h30 NY` (partageant le même `AxisGroup`) ; `Chart` est l'instrument du graphique
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
Un bouton **`Data Snapshot`** dans les réglages de l'add-on affiche l'état complet de la connexion TWS, des barres historiques 1 minute reçues depuis `09h30 NY`, des prix d'ouverture `09h30`, des derniers prix et des performances en `%` de l'instrument du graphique et de chaque action, ainsi que la taille des paniers et l'état de la watchlist.

## Paniers et watchlist CSV

Les paniers viennent d'une **watchlist CSV**, la seule source des paniers : section **`Baskets (watchlist CSV)`** des réglages, **`Browse…`** pour choisir le fichier ou **`Load watchlist`** pour le relire. Le chargement s'applique tout de suite : l'abonnement TWS en cours est relancé sur les nouveaux paniers, sinon ils servent au prochain **`Subscribe`**. La section affiche les paniers chargés (lecture seule). La watchlist est relue au démarrage de l'add-on ; elle n'est jamais rechargée seule en cours de séance. Il n'y a aucun panier par défaut : sans watchlist chargée, **`Subscribe`** refuse de démarrer. Si le fichier est illisible au démarrage, les paniers de son dernier chargement restent.

Format (une ligne par action, en-tête facultatif, séparateur `,` ou `;`, lignes `#` ignorées) :

```csv
basket,symbol,weight
risk,ALAB,
risk,ARM,
refuge,ADBE,
mega,NVDA,5.11
mega,AAPL,5.06
```

- `basket` : `risk`, `refuge` ou `mega` ; `weight` n'est utilisé que pour les méga-caps (obligatoire et positif).
- Au moins 5 actions Risk et 5 Refuge, 3 méga-caps ; au-delà de 30 Risk, 29 Refuge ou 5 méga-caps, les premières lignes sont gardées.
- Les trois paniers sont obligatoires.
- Un fichier invalide ne change rien : les paniers actuels restent et le statut affiche la raison.

**Quant Research** écrit ce fichier depuis son dashboard (onglet Paniers, bouton « Exporter pour Bookmap ») dans `quant/market regime/data/bookmap_watchlist.csv`.

## Limites TWS

- **Lignes de données** : 30 + 29 + 5 = 64 actions au maximum, sur les 100 lignes par défaut d'un compte IB (partagées avec les watchlists TWS et les autres programmes API du compte).
- **Rythme** : IB limite les requêtes à (lignes de données / 2) par seconde, soit 50 par défaut ; l'add-on envoie toutes ses requêtes (abonnements, historiques, annulations) depuis une file unique, espacées de 25 ms (40 par seconde).
- **Ouverture 09h30 NY** : à 09h30:00, TWS renvoie encore l'ouverture de la veille (tick Open) et un historique sans barre du jour. Abonné avant l'ouverture, l'add-on remet la séance à zéro à 09h30 puis redemande l'historique à 09h31:05, une fois la première barre close (jusqu'à 3 nouvelles demandes, une par minute, si TWS n'a pas encore la séance du jour).

## Compilation

```powershell
.\gradlew.bat clean jar
```

Produit :
- `BrunoF_MarketRegime_1.0.2.jar` (JAR autonome incluant `TwsApi.jar` et `protobuf-java`, conforme au [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html))
