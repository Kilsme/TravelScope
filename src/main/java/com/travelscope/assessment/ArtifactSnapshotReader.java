package com.travelscope.assessment;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicit local runtime dependency: middleware receives the inner ReActAgent, not its Harness wrapper. */
public final class ArtifactSnapshotReader {
    public static final String FILESYSTEM_KEY="assessment.artifactFilesystem";
    private ArtifactSnapshotReader(){}
    static String read(RuntimeContext runtime,String path){
        AbstractFilesystem filesystem=runtime.get(FILESYSTEM_KEY);
        if(filesystem==null)throw new IllegalStateException("No execution filesystem association");
        var files=filesystem.downloadFiles(runtime,List.of(path));
        if(files.isEmpty()||!files.getFirst().isSuccess()||files.getFirst().content()==null)
            throw new IllegalStateException("Cannot snapshot this execution's edited artifact");
        return new String(files.getFirst().content(),StandardCharsets.UTF_8);
    }
}
