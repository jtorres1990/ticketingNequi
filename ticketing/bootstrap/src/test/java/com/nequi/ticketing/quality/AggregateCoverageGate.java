package com.nequi.ticketing.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class AggregateCoverageGate {

    @Test
    @DisplayName("NFR-013 aggregate unit-test line coverage is at least 90 percent")
    void aggregateLineCoverageMeetsApprovedMinimum() throws Exception {
        Path report = Path.of(System.getProperty("coverage.report"));
        BigDecimal minimum = new BigDecimal(System.getProperty("coverage.minimum", "0.90"));

        assertThat(report).exists().isRegularFile();

        var documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        try (var input = Files.newInputStream(report)) {
            Element reportElement = documentBuilderFactory.newDocumentBuilder().parse(input).getDocumentElement();
            LineCounter lineCounter = directLineCounter(reportElement);
            BigDecimal ratio = lineCounter.ratio();

            assertThat(ratio)
                    .as("aggregate line coverage from %s", report)
                    .isGreaterThanOrEqualTo(minimum);
        }
    }

    private static LineCounter directLineCounter(Element reportElement) {
        for (Node node = reportElement.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element counter
                    && "counter".equals(counter.getTagName())
                    && "LINE".equals(counter.getAttribute("type"))) {
                return new LineCounter(
                        Integer.parseInt(counter.getAttribute("missed")),
                        Integer.parseInt(counter.getAttribute("covered")));
            }
        }
        return new LineCounter(0, 0);
    }

    private record LineCounter(int missed, int covered) {
        BigDecimal ratio() {
            int total = missed + covered;
            return total == 0
                    ? BigDecimal.ONE
                    : BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        }
    }
}
