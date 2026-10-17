package com.travelscope.assessment;
import com.travelscope.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController @RequestMapping("/admin/assessment")
public class AssessmentController {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ObservationStore observationStore;
    private final AssessmentQueries queries;
    private final AssessmentBatches batches;
    private final AssessmentActions actions;
    public AssessmentController(AssessmentQueries queries,AssessmentBatches batches,AssessmentActions actions){this.queries=queries;this.batches=batches;this.actions=actions;}
    private void admin(HttpServletRequest r){if(!"admin".equals(r.getAttribute(AuthInterceptor.ATTR_USER_ROLE)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");}
    @GetMapping("/samples/{id}/runs") public Object sampleRuns(HttpServletRequest r,@PathVariable UUID id){admin(r);return queries.sampleRuns(id);}
    @GetMapping("/cases") public Object cases(HttpServletRequest r){admin(r);return batches.cases();}
    @PostMapping("/batches") @ResponseStatus(HttpStatus.ACCEPTED) public Object create(HttpServletRequest r,@RequestBody AssessmentBatches.Request body){admin(r);return Map.of("id",batches.create(body));}
    @GetMapping("/batches") public Object batches(HttpServletRequest r){admin(r);return batches.list();}
    @GetMapping("/batches/{id}") public Object batch(HttpServletRequest r,@PathVariable UUID id){admin(r);return batches.detail(id);}
    @PostMapping("/batches/{id}/cancel") public void cancel(HttpServletRequest r,@PathVariable UUID id){admin(r);batches.cancel(id);}
    @PostMapping("/batches/{id}/retry-incomplete") @ResponseStatus(HttpStatus.ACCEPTED) public void retry(HttpServletRequest r,@PathVariable UUID id){admin(r);batches.retry(id);}
    @PostMapping("/runs/{id}/evaluations") @ResponseStatus(HttpStatus.ACCEPTED) public Object evaluate(HttpServletRequest r,@PathVariable UUID id){admin(r);return actions.evaluate(id);}
    @GetMapping("/runs/{id}/evaluations") public Object evaluations(HttpServletRequest r,@PathVariable UUID id){admin(r);return actions.evaluations(id);}
    @GetMapping("/comparisons") public Object compare(HttpServletRequest r,@RequestParam UUID baselineId,@RequestParam UUID candidateId){admin(r);return actions.compare(baselineId,candidateId);}
    @GetMapping("/components") public Object components(HttpServletRequest r,@RequestParam(defaultValue="ONLINE")String source,@RequestParam(required=false)String toolMode,@RequestParam(required=false)String phase,@RequestParam(required=false)String kind,@RequestParam(required=false)UUID batchId){admin(r);return queries.components(source,toolMode,phase,kind,batchId);}
    @GetMapping("/overview") public Object overview(HttpServletRequest r,@RequestParam(required=false)String source,@RequestParam(required=false)String toolMode){admin(r);var result=new LinkedHashMap<String,Object>(queries.overview(source,toolMode));if(observationStore!=null)result.put("ingestion",observationStore.health());return result;}
    @GetMapping("/runs") public Object runs(HttpServletRequest r,@RequestParam(required=false)String source,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){
        admin(r);if(page<0||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(List.of("toolMode","componentKind","componentName","phase","batchId").stream().noneMatch(key->r.getParameter(key)!=null))
            return queries.runs(source,page,size,r.getParameter("taskType"),r.getParameter("status"),r.getParameter("sessionId"));
        UUID batchId=null;try{if(r.getParameter("batchId")!=null)batchId=UUID.fromString(r.getParameter("batchId"));}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"批次编号无效");}
        return queries.runs(source,page,size,r.getParameter("taskType"),r.getParameter("status"),r.getParameter("sessionId"),r.getParameter("toolMode"),r.getParameter("componentKind"),r.getParameter("componentName"),r.getParameter("phase"),batchId);
    }
    @GetMapping("/runs/{id}") public Object run(HttpServletRequest r,@PathVariable UUID id){admin(r);return queries.run(id);}
    @GetMapping("/runs/{id}/spans") public Object spans(HttpServletRequest r,@PathVariable UUID id){admin(r);return queries.spans(id);}
    @GetMapping("/runs/{id}/artifacts") public Object artifacts(HttpServletRequest r,@PathVariable UUID id){admin(r);return queries.artifacts(id);}
    @GetMapping("/spans/{id}/payload") public Object payload(HttpServletRequest r,@PathVariable UUID id){admin(r);return queries.payload(id);}
}
