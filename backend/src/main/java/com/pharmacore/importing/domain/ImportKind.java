package com.pharmacore.importing.domain;

import java.util.List;

/**
 * What a file carries. Each kind declares its fields with the header names seen in exports of
 * the programs found in West African pharmacies (French labels, abbreviations, English variants),
 * so a file is mapped without the pharmacist retyping anything in the common case.
 */
public enum ImportKind {

    /** National drug reference maintained by the editor (platform). */
    CATALOG(List.of(
            Field.required("code", "Code CIP13 ou EAN13", "cip13", "cip", "code cip", "codecip", "ean13", "ean", "code ean",
                    "code barre", "code-barre", "codebarre", "gtin", "code", "code produit", "reference", "ref"),
            Field.required("designation", "Désignation", "designation", "désignation", "libelle", "libellé", "nom",
                    "produit", "nom produit", "article", "denomination", "dénomination", "label", "name"),
            Field.optional("dci", "DCI (molécule)", "dci", "molecule", "molécule", "principe actif", "substance", "inn"),
            Field.optional("form", "Forme", "forme", "forme pharmaceutique", "forme galenique", "forme galénique", "form"),
            Field.optional("dosage", "Dosage", "dosage", "dose", "titrage", "strength"),
            Field.optional("laboratory", "Laboratoire", "laboratoire", "labo", "fabricant", "marque", "laboratory",
                    "manufacturer"),
            Field.optional("atc", "Code ATC", "atc", "code atc", "classe atc"),
            Field.optional("listing", "Liste (I, II, stupéfiant)", "liste", "listing", "tableau", "classe", "ordonnance",
                    "prescription"),
            Field.optional("vatRate", "Taux de TVA (%)", "tva", "taux tva", "taux de tva", "vat", "vat rate", "tx tva"),
            Field.optional("publicPrice", "Prix public TTC", "prix public", "prix de vente", "prix vente", "pv", "pvp",
                    "prix ttc", "ppv", "prix", "price", "public price", "pu vente", "p.v."),
            Field.optional("reimbursementRate", "Taux de remboursement (%)", "remboursement", "taux remboursement",
                    "taux de remboursement", "tr"),
            Field.optional("active", "Actif", "actif", "active", "statut", "status"))),

    /** The pharmacy's own product file: codes, prices, reorder settings (creates missing products). */
    PRODUCTS(List.of(
            Field.required("code", "Code CIP13 ou EAN13", "cip13", "cip", "code cip", "codecip", "ean13", "ean", "code ean",
                    "code barre", "code-barre", "codebarre", "gtin", "code", "code produit", "reference", "ref"),
            Field.required("designation", "Désignation", "designation", "désignation", "libelle", "libellé", "nom",
                    "produit", "nom produit", "article", "denomination", "dénomination", "label", "name"),
            Field.optional("dci", "DCI (molécule)", "dci", "molecule", "molécule", "principe actif", "substance", "inn"),
            Field.optional("form", "Forme", "forme", "forme pharmaceutique", "forme galenique", "forme galénique", "form"),
            Field.optional("dosage", "Dosage", "dosage", "dose", "titrage", "strength"),
            Field.optional("laboratory", "Laboratoire", "laboratoire", "labo", "fabricant", "marque", "laboratory"),
            Field.optional("listing", "Liste (I, II, stupéfiant)", "liste", "listing", "tableau", "classe", "ordonnance"),
            Field.optional("vatRate", "Taux de TVA (%)", "tva", "taux tva", "taux de tva", "vat", "vat rate", "tx tva"),
            Field.optional("sellingPrice", "Prix de vente TTC", "prix de vente", "prix vente", "pv", "pvp", "prix ttc",
                    "prix public", "prix", "price", "pu vente", "p.v.", "prix unitaire"),
            Field.optional("reorderThreshold", "Seuil de réapprovisionnement", "seuil", "stock mini", "stock minimum",
                    "mini", "minimum", "seuil reappro", "seuil réappro", "stock min", "reorder threshold"),
            Field.optional("reorderQuantity", "Quantité à commander", "quantite a commander", "quantité à commander",
                    "qte commande", "qté commande", "reappro", "réappro", "reorder quantity", "stock maxi", "maximum"),
            Field.optional("location", "Emplacement", "emplacement", "rayon", "casier", "etagere", "étagère", "location",
                    "zone"))),

    /** Stock on hand by lot, received as an opening inventory. */
    STOCK(List.of(
            Field.required("code", "Code CIP13 ou EAN13", "cip13", "cip", "code cip", "codecip", "ean13", "ean", "code ean",
                    "code barre", "code-barre", "codebarre", "gtin", "code", "code produit", "reference", "ref"),
            Field.optional("designation", "Désignation", "designation", "désignation", "libelle", "libellé", "nom",
                    "produit", "article", "label", "name"),
            Field.optional("lot", "Numéro de lot", "lot", "numero de lot", "numéro de lot", "n° lot", "no lot", "num lot",
                    "batch", "lot number"),
            Field.optional("expiry", "Date de péremption", "peremption", "péremption", "date peremption", "date de peremption",
                    "date de péremption", "expiration", "date expiration", "dlu", "dluo", "ddp", "exp", "expiry", "expiry date",
                    "perime le", "périme le"),
            Field.required("quantity", "Quantité", "quantite", "quantité", "qte", "qté", "stock", "qty", "quantity",
                    "stock actuel", "en stock", "qte stock", "qté stock"),
            Field.optional("unitCost", "Prix d'achat unitaire HT", "prix achat", "prix d'achat", "prix achat ht",
                    "pa", "p a", "pa ht", "p a ht", "pamp", "cout", "coût", "prix unitaire achat", "pu achat", "cost",
                    "unit cost", "cump", "pump", "prix revient"),
            Field.optional("sellingPrice", "Prix de vente TTC", "prix de vente", "prix vente", "pv", "pvp", "prix ttc",
                    "prix public", "prix", "price", "pu vente", "p v", "pv ttc", "p v ttc"))),

    /** Customer file with credit accounts carried over. */
    CUSTOMERS(List.of(
            Field.required("name", "Nom", "nom", "client", "nom client", "nom complet", "raison sociale", "name",
                    "customer", "denomination", "dénomination", "nom et prenom", "nom et prénom"),
            Field.optional("phone", "Téléphone", "telephone", "téléphone", "tel", "tél", "tel.", "portable", "mobile",
                    "gsm", "phone", "contact", "numero", "numéro"),
            Field.optional("email", "E-mail", "email", "e-mail", "mail", "courriel"),
            Field.optional("address", "Adresse", "adresse", "address", "quartier", "domicile", "localite", "localité"),
            Field.optional("creditLimit", "Plafond de crédit", "plafond", "plafond credit", "plafond crédit", "limite credit",
                    "limite", "credit max", "crédit max", "credit limit", "plafond ardoise"),
            Field.optional("balance", "Solde dû (créance)", "solde", "solde du", "solde dû", "creance", "créance", "du", "dû",
                    "ardoise", "encours", "en cours", "dette", "reste a payer", "reste à payer", "balance", "montant du",
                    "montant dû", "credit", "crédit"),
            Field.optional("notes", "Notes", "notes", "note", "observation", "observations", "commentaire", "remarque"))),

    /** Wholesalers and other suppliers. */
    SUPPLIERS(List.of(
            Field.required("name", "Nom", "nom", "fournisseur", "nom fournisseur", "raison sociale", "societe", "société",
                    "grossiste", "name", "supplier"),
            Field.optional("contact", "Contact", "contact", "interlocuteur", "representant", "représentant", "commercial",
                    "nom contact"),
            Field.optional("phone", "Téléphone", "telephone", "téléphone", "tel", "tél", "tel.", "portable", "mobile",
                    "phone", "numero", "numéro"),
            Field.optional("whatsapp", "WhatsApp", "whatsapp", "wa"),
            Field.optional("email", "E-mail", "email", "e-mail", "mail", "courriel"),
            Field.optional("address", "Adresse", "adresse", "address", "ville", "localite", "localité"),
            Field.optional("leadTimeDays", "Délai de livraison (jours)", "delai", "délai", "delai livraison",
                    "délai de livraison", "delai jours", "lead time", "jours livraison"),
            Field.optional("paymentTerms", "Conditions de paiement", "conditions", "conditions de paiement", "paiement",
                    "reglement", "règlement", "echeance", "échéance", "payment terms"),
            Field.optional("notes", "Notes", "notes", "note", "observation", "observations", "commentaire", "remarque")));

    private final List<Field> fields;

    ImportKind(List<Field> fields) {
        this.fields = fields;
    }

    public List<Field> fields() {
        return fields;
    }

    public Field field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field " + name + " for " + this));
    }

    /** A column of the import, with the header spellings that identify it automatically. */
    public record Field(String name, String label, boolean required, List<String> aliases) {

        static Field required(String name, String label, String... aliases) {
            return new Field(name, label, true, List.of(aliases));
        }

        static Field optional(String name, String label, String... aliases) {
            return new Field(name, label, false, List.of(aliases));
        }
    }
}
