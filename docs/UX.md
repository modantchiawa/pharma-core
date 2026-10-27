# Expérience utilisateur et système de design

## Principes

1. **Le comptoir d'abord.** Une vente se fait sans souris : douchette (code + Entrée), F2
   recherche, F4 espèces, F5 mobile money, F9 valider, Échap annuler le panier. Le champ de
   recherche reprend le focus après chaque action.
2. **Lisible dans une officine ouest-africaine** : lumière forte, écrans d'entrée de gamme,
   journées longues. Trois densités, trois tailles de texte, un mode contraste élevé et un mode
   sombre pour les gardes, réglables **par poste** (menu « Affichage »), indépendamment de la
   charte de la pharmacie (bouton lune/soleil et menu « Affichage » de la barre supérieure).
   Toute couleur de marque reçoit automatiquement une couleur de texte qui respecte le
   contraste WCAG AA.
3. **L'état du système est toujours visible** : pastille En ligne / Hors-ligne, nombre de ventes
   en attente de synchronisation, rejets à traiter, session de caisse ouverte ou non. Jamais
   de blocage : hors-ligne, l'interface explique ce qui est différé, pas ce qui est impossible.
4. **Les règles sont appliquées avant la validation**, avec une phrase claire (« remise
   supérieure à votre plafond (2 %) », « ordonnance requise : renseignez le prescripteur »),
   plutôt qu'un refus serveur après coup.
5. **Argent sans ambiguïté** : montants en F CFA sans décimales, saisie libre (`1250`), rendu
   monnaie en gros, totaux par moyen de paiement sur le rapport Z.
6. **Personnalisable sans développeur** : logo, couleurs, en-tête et pied de ticket, mentions
   légales, préfixe d'ordonnancier, plan de comptes de dépenses, rôles et permissions.

## Système de design

| Élément | Choix |
|---|---|
| Base | Tailwind CSS 4 + feuille `src/styles.css` : jetons de marque (`--brand-primary`, `--brand-secondary`, `--brand-accent`, `--brand-on-primary`) injectés à l'exécution, jetons neutres (`--surface`, `--surface-2`, `--border`, `--text`, `--text-muted`…), sémantiques (`--success`, `--warning`, `--danger`, `--info` et leurs variantes `-soft`), rayons et ombres |
| Composants CSS | `.page`/`.page-header`, `.card`, `.btn` (`-primary`, `-secondary`, `-ghost`, `-danger`, `-soft`, `-sm`, `-lg`, `-icon`), `.input`/`.select`/`.label`/`.input-with-icon`, `.badge-*`, `.alert-*`, `.kpi`, `.data-table`/`.num`, `.segmented`, `.empty-state`, `.kbd`, `.skeleton` ; les pages n'utilisent que des jetons, jamais de couleur codée en dur |
| Typographie | Inter Variable embarquée (`@fontsource-variable`, aucune requête réseau), chiffres tabulaires dans les tableaux, 16 px × échelle 1 / 1,15 / 1,3 |
| Icônes | Lucide, importées une à une (`shared/ui/icons.ts`, noms sémantiques : `stock`, `cash`, `insurance`…) et dessinées par `<app-icon>` en SVG sans `innerHTML` |
| Navigation | Barre latérale groupée (Comptoir, Stock et achats, Gestion, Réseau, Administration), filtrée par permission, repliable (mémorisé par poste), tiroir sur mobile ; palette de commandes **Ctrl/⌘ K** pour atteindre une page ou une action au clavier |
| Couleurs | Marque de la pharmacie pour les actions principales ; sémantique fixe : vert = en ligne / excédent, ambre = hors-ligne / avertissement, rouge = blocage / manquant |
| Densité | `data-density="compact|comfortable|large"` sur `<html>` : `--density` multiplie hauteurs de contrôles (`--control-h`) et espacements |
| Mode sombre | `data-theme="dark"` : surfaces et jetons redéfinis (y compris la palette Tailwind utilisée par d'anciens écrans), conservé par poste ; `data-contrast="high"` renforce bordures, textes et focus |
| Retours d'action | Notifications brèves (`ToastService`, `<app-toaster>`) en bas au centre : confirmation 3,5 s, erreur 7 s, quatre au plus ; jamais sur le champ de scan ni sur les boutons de validation |
| Logo | Rubrique en tête de « Identité visuelle » : glisser-déposer ou choix de fichier, PNG/JPEG/WebP/SVG, image réduite à 512 px dans le navigateur (SVG converti en PNG, refusé par le serveur), aperçus barre latérale / connexion / ticket noir et blanc, suppression ; logo mis en cache pour l'affichage immédiat et hors ligne, et repris comme icône d'onglet |
| Mise en route | Carte « Mise en route de la pharmacie » sur le pilotage pour le titulaire : logo, couleurs, annuaire public ; chaque étape se coche seule, la carte disparaît une fois tout fait ou masquée |
| Miniatures | `<app-product-thumb>` carré à coins arrondis, fond `--surface-2` : photo de la boîte quand la pharmacie en a pris une, sinon initiale sur une teinte dérivée du nom et pictogramme de la forme galénique (comprimé, gélule, sirop, injectable, aérosol, gel, sachet, accessoire) ; chargée à l'entrée à l'écran, gardée hors ligne. Résultats et panier de la caisse, liste des stocks, péremptions, lignes de commande et réception, fiche produit (grande photo : prise de vue à l'appareil photo sur mobile, choix de fichier, glisser-déposer, suppression) |
| Poste inactif | Avertissement en bas à droite 30 s avant le verrouillage (anneau qui se vide, « Je suis là »), puis écran verrouillé : page floutée, carte centrée avec l'utilisateur, PIN ou mot de passe, « Changer d'utilisateur », fermeture automatique annoncée ; bouton cadenas de la barre supérieure et **Ctrl/⌘ L** pour verrouiller tout de suite |
| Graphiques | Une série = couleur de marque, sans légende ; axe aux graduations rondes, colonnes à extrémité arrondie, infobulle au survol et au focus clavier, vue tableau équivalente |
| Langue | `LOCALE_ID` `fr` : dates (« mardi 13 octobre ») et nombres (« 28,5 % ») en français ; les montants passent par `MoneyPipe` |
| Composants | Angular 22 standalone, signals, contrôle de flux natif ; aucun framework de composants lourd |
| Formulaires | Validation côté client miroir des règles serveur ; messages en français, orientés action |
| Impression | Feuille `@media print` : ticket 58/80 mm, facture A4, rapport Z ; le reste de l'écran est masqué |
| Accessibilité | Libellés ARIA sur les champs et boutons-icônes, listes `role="listbox"` navigables au clavier, contraste calculé, focus visible, animations désactivées si `prefers-reduced-motion` |

## Parcours clés

* **Ouverture de journée** : connexion par PIN → ouverture de caisse (fond) → vente.
* **Vente** : scan → quantités/remises → client (facultatif) → paiements (plusieurs moyens,
  crédit si autorisé) → F9 → ticket imprimé → synchronisation silencieuse.
* **Fermeture** : rapport X pour contrôler → comptage → clôture (rapport Z, écart passé en
  comptabilité) → fiche de commande du lendemain (proposée, ajustée, envoyée par WhatsApp).
* **Réception** : livraison du grossiste → saisie lot/péremption/quantité/prix → stock, prix de
  vente et écriture d'achat mis à jour d'un coup.
* **Pilotage** : compte de résultat et bilan du mois, balance, export CSV pour le comptable ;
  créances clients ; serveur local (état de synchronisation, sauvegardes).
