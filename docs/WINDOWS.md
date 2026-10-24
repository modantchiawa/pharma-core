# Installation en officine (Windows) et résilience

## 1. Topologie recommandée

```
            ┌──────────────────────────── Officine ───────────────────────────┐
            │                                                                  │
 Internet   │   Box / routeur 4G ──── onduleur (UPS) ──── Hub (PC Windows)     │
 (intermittent) │        │                                 PostgreSQL + API + PWA │
            │        │ Wi-Fi / Ethernet                                         │
            │   ┌────┴─────┬─────────────┬──────────────┐                       │
            │  Caisse 1   Caisse 2   Comptoir/        Bureau du                 │
            │  (PWA)      (PWA)      préparation (PWA) titulaire (PWA)          │
            └──────────────────────────────────────────────────────────────────┘
                                   │ synchronisation quand Internet répond
                                   ▼
                          PharmaCore Cloud (réplique, réseau, supervision)
```

* **Hub** : un PC fixe (Windows 10/11, 4 Go RAM, SSD conseillé) qui ne sert qu'à cela, de
  préférence le poste du bureau. Il héberge la base, l'API et sert l'application aux autres
  postes. Il doit être sur onduleur avec le routeur.
* **Postes** : n'importe quel PC ou tablette avec Edge/Chrome. L'application est « installée »
  depuis `http://<nom-du-hub>:8080` (PWA). Chaque poste garde son propre journal de ventes.
* **Petite officine (1 poste)** : le hub et la caisse sont le même PC.
* **Sans hub** : une pharmacie peut travailler directement sur le cloud (PWA avec journal
  hors-ligne). Elle perd le multi-postes hors-ligne et les rapports sans Internet.

## 2. Installer le hub

1. Télécharger `PharmaCoreHub-<version>.zip` (construit par le workflow `Hub Windows package`),
   le décompresser dans un dossier temporaire.
2. Ouvrir PowerShell **en administrateur** et exécuter :
   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\install-hub.ps1 -CloudUrl https://cloud.pharmacore.io
   ```
   Le script installe dans `C:\PharmaCore`, initialise PostgreSQL (rôles de production, mots
   de passe générés et protégés par ACL), enregistre deux services Windows redémarrant
   automatiquement, ouvre le port 8080 sur le réseau privé et planifie la sauvegarde.
3. Ouvrir `http://localhost:8080` : l'assistant de premier démarrage demande le **code
   d'appairage** (8 caractères, valable 24 h) que l'éditeur génère depuis la console de
   supervision. Cette étape, et elle seule, exige Internet.
4. Le hub télécharge le catalogue national et les données de la pharmacie (rôles, compte du
   titulaire, plan comptable), puis se synchronise toutes les 30 secondes.
5. Se connecter avec l'e-mail du titulaire et le mot de passe temporaire remis par l'éditeur ;
   l'application impose d'en choisir un nouveau, puis un code PIN.
6. Sur chaque poste : ouvrir `http://pharmacore-hub:8080` (nommer le PC du hub
   `pharmacore-hub`, ou utiliser son adresse IP fixe), cliquer « Installer l'application »
   dans le navigateur, se connecter une première fois avec un mot de passe (le poste est alors
   « ouvert » pour 30 jours, les collègues basculent par PIN).

Mise à jour : relancer `install-hub.ps1` avec le nouveau paquet ; les données et secrets sont
conservés, Flyway migre la base au démarrage.

## 3. Ce qui se passe en cas de panne

| Panne | Effet | Reprise |
|---|---|---|
| **Internet coupé** (heures, jours, semaines) | Aucun sur la vente, les stocks, la caisse, les commandes, la compta. Indisponibles : réseau inter-pharmacies, messagerie, mises à jour du catalogue. | Automatique : le hub pousse le retard par lots dès que le cloud répond ; une coupure plus longue que la rétention du journal cloud (30 jours) déclenche un rechargement complet, sans perte. |
| **Coupure de courant** (hub sur onduleur) | Les postes sur batterie continuent ; sinon arrêt propre du hub par l'onduleur. | PostgreSQL écrit en mode synchrone (`fsync`, `full_page_writes`) : rien de validé n'est perdu. Les services redémarrent seuls au retour du courant. |
| **Hub en panne** (matériel) | Les postes passent en mode dégradé : vente possible sur le catalogue en cache, journal local conservé ; pas de stock partagé ni de rapports. | Installer le hub sur un autre PC, restaurer la dernière sauvegarde (`restore-hub.ps1`) **ou** appairer le nouveau hub : il recharge la réplique cloud (y compris les ventes déjà synchronisées). Les postes renvoient ensuite leur journal. |
| **Poste de caisse perdu** avant synchronisation | Les ventes non envoyées de ce poste seules sont perdues (elles sont dans IndexedDB du navigateur, envoyées toutes les 15 s dès que le hub répond). | Réduire le risque : hub joignable en permanence sur le LAN, onduleur sur le switch Wi-Fi. |
| **Wi-Fi instable** | Le poste bascule hors-ligne/en ligne sans intervention. | Journal local + file de synchronisation avec backoff. |
| **Erreur humaine** (vente annulée à tort, suppression) | Toute action sensible est dans le journal d'audit inaltérable ; les ventes s'annulent par contre-passation, jamais par suppression. | Historique complet, sauvegardes quotidiennes 14 jours + USB + réplique cloud. |

## 4. Sauvegardes

* **Locale** : `pg_dump` compressé chaque soir à 22:30 et à chaque démarrage, 14 jours
  conservés dans `C:\PharmaCore\backups`.
* **USB** : toute clé contenant un dossier `PharmaCoreBackup` reçoit la dernière sauvegarde
  (7 conservées). Recommandé : deux clés en rotation, une hors de l'officine.
* **Cloud** : réplique continue de toutes les tables de la pharmacie (hors sessions et
  secrets locaux) par la synchronisation ; c'est aussi la source de restauration d'un hub
  réinstallé.
* **Test de restauration** : `restore-hub.ps1 -DumpFile <fichier>` ; l'ancienne base est
  conservée sous un autre nom.

## 5. Sécurité du poste hub

* Services sous le compte SYSTEM, mots de passe de base dans `data\hub.env` lisible par les
  administrateurs seuls, clé privée de signature dans `data\keys` (même protection).
* PostgreSQL n'écoute que sur `127.0.0.1` ; les postes passent par l'API (8080), le pare-feu
  n'ouvre ce port que pour le profil réseau « privé ».
* Les jetons des utilisateurs sont signés par le hub ; le cloud en vérifie la signature grâce
  à la clé publique publiée à l'appairage. Aucun secret partagé.
* Chaque poste est lié par un jeton de 30 jours ; la bascule PIN est verrouillée 15 minutes
  après 5 échecs.

## 6. Dimensionnement et matériel

| Officine | Hub | Postes |
|---|---|---|
| 1 à 2 postes, < 300 ventes/jour | Le PC de caisse principal (Core i3 / 8 Go) | Même PC + 1 |
| 3 à 6 postes | Mini-PC dédié (Core i5 / 8 Go / SSD 256 Go) + onduleur 650 VA | PC ou tablettes Windows/Android |
| > 6 postes ou plusieurs officines d'un groupe | Mini-PC dédié par officine ; consolidation dans le cloud | — |

Imprimantes thermiques 80 mm USB (pilote Windows générique ou ESC/POS), douchettes USB en
mode clavier (suffixe Entrée), lecteur de codes-barres 1D/2D pour les CIP13/Datamatrix.
