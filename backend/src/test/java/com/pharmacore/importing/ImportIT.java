package com.pharmacore.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.importing.domain.TabularReadersTest;
import com.pharmacore.support.IntegrationTest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Migration from another program: the editor loads a drug reference, then a pharmacy imports its
 * product file (Excel), its stock (Windows CSV), its customers with their debts and its suppliers.
 */
class ImportIT extends IntegrationTest {

    private static final String DOLIPRANE = code("340093000000");
    private static final String AMOXICILLINE = code("340093000001");
    private static final String TRAMADOL = code("340093000002");
    private static final String BAD = code("340093000003");
    private static final String SAVON = code("618123456789");

    private static String code(String first12) {
        return first12 + com.pharmacore.catalog.domain.Cip13.checkDigit(first12);
    }

    @Test
    void editorAndPharmacyMigrateTheirFilesWithPreviewDryRunAndReport() throws Exception {
        var pharmacy = onboardPharmacy("Migration");

        // --- platform: national reference, preview then run; a bad VAT line is reported, not fatal
        String reference = "﻿CIP;Désignation;DCI;Liste;TVA;Prix public\n"
                + "" + DOLIPRANE + ";DOLIPRANE 1000MG CPR B/8;PARACETAMOL;;0;1 250\n"
                + "" + AMOXICILLINE + ";AMOXICILLINE 1G CPR B/14;AMOXICILLINE;Liste I;0;3 500\n"
                + "" + TRAMADOL + ";TRAMADOL 50MG GEL;TRAMADOL;Tableau B;0;2 000\n"
                + "" + BAD + ";MAUVAISE TVA;;;abc;100\n";
        var preview = mvc.perform(upload("/api/platform/catalog/imports/preview", "ref.csv", reference.getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("CSV"))
                .andExpect(jsonPath("$.delimiter").value(";"))
                .andExpect(jsonPath("$.rows").value(4))
                .andExpect(jsonPath("$.missingRequired").isEmpty())
                .andReturn().getResponse().getContentAsString();
        List<Integer> mapped = JsonPath.read(preview, "$.fields[?(@.name == 'publicPrice')].column");
        assertThat(mapped).containsExactly(5);
        assertThat((String) JsonPath.read(preview, "$.sample[3].error")).contains("Pourcentage invalide");

        mvc.perform(upload("/api/platform/catalog/imports", "ref.csv", reference.getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(5));
        call(get("/api/v1/catalog/products?q=" + TRAMADOL), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].data.listing").value("STUPEFIANT"))
                .andExpect(jsonPath("$[0].data.publicPriceCents").value(200_000));

        // --- pharmacy: product file from Excel, with a product unknown to the reference and one without code
        byte[] products = TabularReadersTest.xlsx("""
                <sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
                  <row r="2"><c r="A2"><v>%s</v></c><c r="B2" t="s"><v>4</v></c><c r="C2"><v>1300</v></c><c r="D2"><v>10</v></c></row>
                  <row r="3"><c r="A3"><v>%s</v></c><c r="B3" t="s"><v>5</v></c><c r="C3"><v>750</v></c><c r="D3"><v>5</v></c></row>
                  <row r="4"><c r="B4" t="s"><v>6</v></c><c r="C4"><v>2500</v></c></row>
                </sheetData>""".formatted(DOLIPRANE, SAVON), "Code barre", "Produit", "PV", "Stock mini", "DOLIPRANE 1000MG CPR B/8",
                "SAVON DERMATOLOGIQUE 100G", "PRÉPARATION MAGISTRALE X");
        var dry = send(upload("/api/v1/imports", "produits.xlsx", products).param("kind", "PRODUCTS").param("dryRun", "true"), pharmacy.ownerToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.rejected").value(0));
        assertThat(dry).isNotNull();
        // nothing was written by the dry run
        call(get("/api/v1/catalog/products?q=SAVON"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        send(upload("/api/v1/imports", "produits.xlsx", products).param("kind", "PRODUCTS"), pharmacy.ownerToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));
        call(get("/api/v1/catalog/products?q=SAVON"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[0].cip13").value(SAVON));
        String internal = read(call(get("/api/v1/catalog/products?q=MAGISTRALE"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[0].cip13").value(org.hamcrest.Matchers.startsWith("200"))), "$[0].cip13");
        // re-importing the same file maps the code-less product onto the same internal code
        send(upload("/api/v1/imports", "produits.xlsx", products).param("kind", "PRODUCTS"), pharmacy.ownerToken())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(3));
        call(get("/api/v1/catalog/products?q=MAGISTRALE"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cip13").value(internal));

        // --- stock: Windows CSV with month-only expiry, a missing expiry covered by the default, an expired lot rejected
        String stock = "Code CIP,Libelle,Lot,Peremption,Qte,PA\n"
                + "" + DOLIPRANE + ",DOLIPRANE,L1,09/2028,48,800\n"
                + "" + AMOXICILLINE + ",AMOXICILLINE,,,10,2 000\n"
                + "" + TRAMADOL + ",TRAMADOL,L9,01/2020,5,1 500\n";
        send(upload("/api/v1/imports", "stock.csv", stock.getBytes(Charset.forName("windows-1252")))
                        .param("kind", "STOCK").param("defaultExpiry", LocalDate.now().plusYears(1).toString()), pharmacy.ownerToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(4));
        var doliprane = JsonPath.read(call(get("/api/v1/catalog/products?q=" + DOLIPRANE), pharmacy.ownerToken(), null)
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        assertThat(available(pharmacy, java.util.UUID.fromString(doliprane.toString()))).isEqualTo(48);

        // --- customers with their debts; the second run finds them by phone and creates nothing
        String customers = "Client;Tél;Plafond;Ardoise\nMme Hadiza Moussa;+22790000000;50 000;12 500\nM. Sani;;0;0\n";
        send(upload("/api/v1/imports", "clients.csv", customers.getBytes(StandardCharsets.UTF_8)).param("kind", "CUSTOMERS"), pharmacy.ownerToken())
                .andExpect(jsonPath("$.created").value(2));
        call(get("/api/v1/customers/debtors"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[0].displayName").value("Mme Hadiza Moussa"))
                .andExpect(jsonPath("$[0].creditBalanceCents").value(1_250_000));
        send(upload("/api/v1/imports", "clients.csv", customers.getBytes(StandardCharsets.UTF_8)).param("kind", "CUSTOMERS"), pharmacy.ownerToken())
                .andExpect(jsonPath("$.created").value(1))   // M. Sani has no phone: cannot be matched
                .andExpect(jsonPath("$.updated").value(1));

        // --- suppliers, then the job history and the template
        String suppliers = "Fournisseur;Téléphone;Délai\nLaborex Niger;+22720000000;1\nCopharni;;2\n";
        send(upload("/api/v1/imports", "fournisseurs.csv", suppliers.getBytes(StandardCharsets.UTF_8)).param("kind", "SUPPLIERS"), pharmacy.ownerToken())
                .andExpect(jsonPath("$.created").value(2));
        call(get("/api/v1/imports"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].kind").value("SUPPLIERS"));
        call(get("/api/v1/imports/templates/STOCK"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Numéro de lot"));
        // the national reference is not importable from a pharmacy account
        send(upload("/api/v1/imports", "ref.csv", reference.getBytes(StandardCharsets.UTF_8)).param("kind", "CATALOG"), pharmacy.ownerToken())
                .andExpect(status().isUnprocessableContent());
    }

    private org.springframework.test.web.servlet.ResultActions send(MockMultipartHttpServletRequestBuilder request, String token)
            throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private static MockMultipartHttpServletRequestBuilder upload(String url, String name, byte[] content) {
        return multipart(url).file(new MockMultipartFile("file", name, "application/octet-stream", content));
    }
}
