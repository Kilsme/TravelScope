package com.travelscope.assessment;
import io.agentscope.core.model.ChatUsage;
/** AgentScope represents absent fields as primitive zero: preserve positive snapshots, never sum cumulative packets. */
final class UsageSnapshot {
    private int input,output,cached;private boolean observed;
    synchronized void accept(ChatUsage usage){
        if(usage==null)return;
        if(usage.getInputTokens()>0){input=usage.getInputTokens();observed=true;}
        if(usage.getOutputTokens()>0){output=usage.getOutputTokens();observed=true;}
        if(usage.getCachedTokens()>0){cached=usage.getCachedTokens();observed=true;}
    }
    synchronized ChatUsage value(){return observed?new ChatUsage(input,output,cached,0d):null;}
}
