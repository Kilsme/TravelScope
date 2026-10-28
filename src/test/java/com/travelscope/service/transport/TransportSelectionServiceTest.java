package com.travelscope.service.transport;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.TaskWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TransportSelectionServiceTest {
    @TempDir Path temp;

    @Test
    void parsesRealtimeResultsAndWritesStructuredContract() {
        TaskWorkspace workspace = new TaskWorkspace(new CollabFileWriter(temp.toString()), new CollabFileReader());
        TransportSelectionService service = new TransportSelectionService(workspace);
        String outbound = json("G7331", "08:30:00", "10:30:00", "180.00", "直达");
        String inbound = json("G7332", "19:00:00", "21:00:00", "190.00", "直达");

        var selected = service.selectAndWrite("u1", "conv-d2", outbound, inbound,
                TransportOptionSelector.SelectionPolicy.defaults());

        assertThat(selected.outbound().selected().serviceNo()).isEqualTo("G7331");
        assertThat(workspace.readTransportSelection("u1", "conv-d2")).contains(selected);
        assertThat(temp.resolve("u1/tasks/conv-d2/transport_selection.json")).exists();
    }

    private static String json(String no, String dep, String arr, String price, String journeyType) {
        return "{\"count\":1,\"source\":\"飞猪\",\"trains\":[{\"price\":\"" + price
                + "\",\"totalDuration\":\"120\",\"journeyType\":\"" + journeyType
                + "\",\"source\":\"飞猪\",\"segments\":[{\"trainNo\":\"" + no
                + "\",\"depTime\":\"2026-10-25 " + dep + "\",\"arrTime\":\"2026-10-25 "
                + arr + "\"}]}]}";
    }
}
