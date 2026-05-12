package com.banka1.bankingcore.account.domain.enums;

/**
 * PR_19 C19.X: minimalan Currency enum koji MarginAccount referencira.
 * Spec (Marzni_Racuni.txt): margin racun je iskljucivo RSD; ostale valute
 * se dodaju ako se u budućoj verziji uvedu FX margin pozicije.
 */
public enum Currency {
    RSD,
    EUR,
    USD,
    CHF,
    GBP,
    JPY
}
