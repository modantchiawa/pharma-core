package com.pharmacore.demo;

import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.inventory.application.ProductImageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Photos of the demo products sold most at the counter: plain boxes drawn for the demonstration
 * (generic name, strength and contents, no brand nor laboratory mark), 256 px JPEG files under
 * {@code demo/products}. The brand-name products keep the placeholder thumbnail.
 */
final class DemoProductImages {

    static final Map<String, String> IMAGES = Map.ofEntries(
            Map.entry("PARACETAMOL 500MG CPR B/16", "paracetamol-500"),
            Map.entry("PARACETAMOL BIOGARAN 1000MG CPR B/8", "paracetamol-1000"),
            Map.entry("AMOXICILLINE 500MG GEL B/12", "amoxicilline-500"),
            Map.entry("AMOXICILLINE 1G CPR B/14", "amoxicilline-1g"),
            Map.entry("ARTESUNATE 60MG INJ", "artesunate-60"),
            Map.entry("METFORMINE 850MG CPR B/30", "metformine-850"),
            Map.entry("AMLODIPINE 5MG CPR B/30", "amlodipine-5"),
            Map.entry("OMEPRAZOLE 20MG GEL B/14", "omeprazole-20"),
            Map.entry("IBUPROFENE 400MG CPR B/20", "ibuprofene-400"),
            Map.entry("SRO SACHETS B/10", "sro"),
            Map.entry("ZINC 20MG CPR B/10", "zinc-20"),
            Map.entry("VITAMINE C 500MG CPR B/30", "vitamine-c-500"),
            Map.entry("CHLOROQUINE 100MG CPR B/20", "chloroquine-100"),
            Map.entry("COTRIMOXAZOLE 480MG CPR B/20", "cotrimoxazole-480"),
            Map.entry("SALBUTAMOL 100µG AEROSOL", "salbutamol-100"),
            Map.entry("DICLOFENAC GEL 1% 50G", "diclofenac-gel"));

    private DemoProductImages() {
    }

    static void seed(ProductImageService images, List<ProductEntity> products) {
        for (ProductEntity product : products) {
            String file = IMAGES.get(product.designation());
            if (file != null) {
                images.replace(product.id(), read("demo/products/" + file + ".jpg"));
            }
        }
    }

    private static byte[] read(String resource) {
        try (InputStream in = DemoProductImages.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing demo resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
