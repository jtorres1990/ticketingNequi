package com.nequi.ticketing.domain.event;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.ValidationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record InventoryDefinition(List<Section> sections, List<ComplimentaryRange> complimentaryRanges) {

    /** Field names of the HTTP contract used to report validation failures (ADR-035). */
    private static final String SECTIONS = "inventory.sections";
    private static final String COMPLIMENTARY = "inventory.complimentary";

    public InventoryDefinition {
        sections = List.copyOf(required(sections, "sections"));
        complimentaryRanges = List.copyOf(required(complimentaryRanges, "complimentaryRanges"));
    }

    /** Section codes in definition order; usable as availability filter (ADR-040). */
    public List<String> sectionCodes() {
        return sections.stream().map(Section::code).toList();
    }

    public boolean definesSection(String sectionCode) {
        return sectionCode != null && sections.stream().anyMatch(section -> section.code().equals(sectionCode));
    }

    /**
     * BR-033: true when {@code ticketId} is {@code <section>-<row>-<seat>} for a seat of this definition.
     * Used to reject unknown tickets before the reservation transaction (ADR-023).
     */
    public boolean containsTicket(String ticketId) {
        if (ticketId == null) {
            return false;
        }
        for (Section section : sections) {
            String sectionPrefix = section.code() + "-";
            if (!ticketId.startsWith(sectionPrefix)) {
                continue;
            }
            String rowAndSeat = ticketId.substring(sectionPrefix.length());
            for (Row row : section.rows()) {
                String rowPrefix = row.label() + "-";
                if (rowAndSeat.startsWith(rowPrefix)
                        && isSeatOf(rowAndSeat.substring(rowPrefix.length()), row.seats())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Number of seats covered by complimentary ranges; exact once the definition is validated. */
    public int complimentarySeatCount() {
        return complimentaryRanges.stream().mapToInt(range -> range.toSeat() - range.fromSeat() + 1).sum();
    }

    private static boolean isSeatOf(String seatText, int seats) {
        if (seatText.isEmpty() || seatText.length() > 4 || seatText.charAt(0) == '0') {
            return false;
        }
        for (int index = 0; index < seatText.length(); index++) {
            char digit = seatText.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        int seat = Integer.parseInt(seatText);
        return seat >= 1 && seat <= seats;
    }

    public ValidatedInventory validate(int declaredCapacity, InventoryLimits limits) {
        required(limits, "limits");
        if (declaredCapacity < 1 || declaredCapacity > limits.maximumCapacity()) {
            throw new ValidationException("capacity", "capacity must be between 1 and " + limits.maximumCapacity());
        }
        if (sections.isEmpty() || sections.size() > limits.maximumSections()) {
            throw new ValidationException(SECTIONS, "section count is outside configured limits");
        }

        Set<String> sectionCodes = new HashSet<>();
        int rows = 0;
        int seats = 0;
        for (Section section : sections) {
            if (!sectionCodes.add(section.code())) {
                throw new ValidationException(SECTIONS, "section codes must be unique");
            }
            Set<String> rowLabels = new HashSet<>();
            for (Row row : section.rows()) {
                rows++;
                if (!rowLabels.add(row.label())) {
                    throw new ValidationException(SECTIONS, "row labels must be unique inside a section");
                }
                if (row.seats() < 1 || row.seats() > limits.maximumSeatsPerRow()) {
                    throw new ValidationException(SECTIONS, "seats per row are outside configured limits");
                }
                seats = Math.addExact(seats, row.seats());
            }
        }
        if (rows < 1 || rows > limits.maximumRows()) {
            throw new ValidationException(SECTIONS, "row count is outside configured limits");
        }
        if (seats != declaredCapacity) {
            throw new ValidationException("capacity", "capacity must equal the derived seat count");
        }
        if (complimentaryRanges.size() > limits.maximumComplimentaryRanges()) {
            throw new ValidationException(COMPLIMENTARY, "complimentary range count is outside configured limits");
        }

        validateRanges();
        List<TicketSeed> tickets = generateTickets();
        return new ValidatedInventory(tickets, partition(tickets, limits.provisioningBatchSize()));
    }

    private void validateRanges() {
        var sorted = complimentaryRanges.stream()
                .sorted(Comparator.comparing(ComplimentaryRange::section)
                        .thenComparing(ComplimentaryRange::row)
                        .thenComparingInt(ComplimentaryRange::fromSeat))
                .toList();
        ComplimentaryRange previous = null;
        for (ComplimentaryRange range : sorted) {
            Row row = findRow(range.section(), range.row());
            if (range.fromSeat() < 1 || range.toSeat() < range.fromSeat() || range.toSeat() > row.seats()) {
                throw new ValidationException(COMPLIMENTARY, "complimentary range is outside its row");
            }
            if (previous != null && previous.sameRow(range) && range.fromSeat() <= previous.toSeat()) {
                throw new ValidationException(COMPLIMENTARY, "complimentary ranges must not overlap");
            }
            previous = range;
        }
    }

    private Row findRow(String sectionCode, String rowLabel) {
        return sections.stream()
                .filter(section -> section.code().equals(sectionCode))
                .flatMap(section -> section.rows().stream())
                .filter(row -> row.label().equals(rowLabel))
                .findFirst()
                .orElseThrow(() -> new ValidationException(COMPLIMENTARY, "complimentary range must reference an existing row"));
    }

    private List<TicketSeed> generateTickets() {
        List<TicketSeed> result = new ArrayList<>();
        sections.stream().sorted(Comparator.comparing(Section::code)).forEach(section ->
                section.rows().stream().sorted(Comparator.comparing(Row::label)).forEach(row -> {
                    for (int seat = 1; seat <= row.seats(); seat++) {
                        int currentSeat = seat;
                        boolean complimentary = complimentaryRanges.stream().anyMatch(range ->
                                range.contains(section.code(), row.label(), currentSeat));
                        result.add(new TicketSeed(section.code(), row.label(), seat, complimentary));
                    }
                }));
        return List.copyOf(result);
    }

    private static List<List<TicketSeed>> partition(List<TicketSeed> tickets, int batchSize) {
        List<List<TicketSeed>> batches = new ArrayList<>();
        for (int start = 0; start < tickets.size(); start += batchSize) {
            batches.add(List.copyOf(tickets.subList(start, Math.min(start + batchSize, tickets.size()))));
        }
        return List.copyOf(batches);
    }

    public record Section(String code, List<Row> rows) {
        public Section {
            code = required(code, "section code");
            rows = List.copyOf(required(rows, "rows"));
            if (rows.isEmpty()) {
                throw new ValidationException(SECTIONS, "a section must contain at least one row");
            }
        }
    }

    public record Row(String label, int seats) {
        public Row {
            label = required(label, "row label");
        }
    }

    public record ComplimentaryRange(String section, String row, int fromSeat, int toSeat) {
        public ComplimentaryRange {
            section = required(section, "complimentary section");
            row = required(row, "complimentary row");
        }

        boolean contains(String candidateSection, String candidateRow, int seat) {
            return section.equals(candidateSection) && row.equals(candidateRow)
                    && seat >= fromSeat && seat <= toSeat;
        }

        boolean sameRow(ComplimentaryRange other) {
            return section.equals(other.section) && row.equals(other.row);
        }
    }

    public record TicketSeed(String section, String row, int seat, boolean complimentary) {
        public String ticketId() {
            return section + "-" + row + "-" + seat;
        }
    }

    public record ValidatedInventory(List<TicketSeed> tickets, List<List<TicketSeed>> batches) {
        public ValidatedInventory {
            tickets = List.copyOf(tickets);
            batches = List.copyOf(batches);
        }

        public long complimentaryCount() {
            return tickets.stream().filter(TicketSeed::complimentary).count();
        }
    }
}
