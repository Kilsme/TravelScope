package com.travelscope.service.transport;

import com.travelscope.collab.TaskWorkspace;
import com.travelscope.collab.TransportSelection;
import org.springframework.stereotype.Service;

/** 解析既有实时结果、确定性选择方案并落 transport_selection.json；不接入路线求解。 */
@Service
public class TransportSelectionService {
    private final TaskWorkspace workspace;
    private final TransportOptionParser parser = new TransportOptionParser();
    private final TransportOptionSelector selector = new TransportOptionSelector();

    public TransportSelectionService(TaskWorkspace workspace) {
        this.workspace = workspace;
    }

    public TransportSelection selectAndWrite(String userId, String sessionId,
                                             String outboundRealtimeJson, String inboundRealtimeJson,
                                             TransportOptionSelector.SelectionPolicy policy) {
        TransportSelection selection = selector.select(parser.parse(outboundRealtimeJson),
                parser.parse(inboundRealtimeJson), policy);
        workspace.writeTransportSelection(userId, sessionId, selection);
        return selection;
    }
}
