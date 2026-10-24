package com.pharmacore.insurance.domain;

/** Who pays the covered share: a mutual, a private insurer, a public scheme or an employer agreement. */
public enum InsurerKind {
    MUTUELLE,
    ASSURANCE,
    ETAT,
    ENTREPRISE
}
