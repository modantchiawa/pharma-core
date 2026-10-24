# Expérience utilisateur et système de design

## Principes

1. **Le comptoir d'abord.** Une vente se fait sans souris : douchette (code + Entrée), F2
   recherche, F4 espèces, F5 mobile money, F9 valider, Échap annuler le panier. Le champ de
   recherche reprend le focus après chaque action.
2. **Lisible dans une officine ouest-africaine** : lumière forte, écrans d'entrée de gamme,
   journées longues. Trois densités, trois tailles de texte, un mode contraste élevé et un mode
   sombre pour les gardes, réglables **par poste** (menu « Affichage »), indépendamment de la
   charte de la pharmacie. Toute couleur de marque reçoit automatiquement une couleur de texte
   qui respecte le contraste WCAG AA.
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
| Base | Tailwind CSS 4, jetons CSS (`--brand-primary`, `--brand-secondary`, `--brand-accent`, `--brand-on-primary`, `--font-scale`, `--row-pad`) |
| Typographie | Police système (rendu immédiat hors-ligne), 16 px × échelle 1 / 1,15 / 1,3 |
| Couleurs | Marque de la pharmacie pour les actions principales ; sémantique fixe : vert = en ligne / excédent, ambre = hors-ligne / avertissement, rouge = blocage / manquant |
| Densité | `data-density="compact|comfortable|large"` sur `<html>` : hauteur des lignes de tableaux et des contrôles (48 px minimum en « grande ») |
| Mode sombre | `data-theme="dark"`, palette ardoise, conservé par poste |
| Composants | Angular 22 standalone, signals, contrôle de flux natif ; aucun framework de composants lourd (poids initial ≈ 500 kB) |
| Formulaires | Validation côté client miroir des règles serveur ; messages en français, orientés action |
| Impression | Feuille `@media print` : ticket 80 mm, rapport Z ; le reste de l'écran est masqué |
| Accessibilité | Libellés ARIA sur les champs, listes `role="listbox"` navigables au clavier, contraste calculé, focus visible |

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
