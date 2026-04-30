package test.demo.apsmodule.recut;

import java.util.ArrayList;
import java.util.List;

public class ReCutCommand {

    private String planId;
    private String baseRevisionId;
    private List<Integer> selectedSequenceNumbers = new ArrayList<>();
    private List<String> selectedSequenceGroupIds = new ArrayList<>();

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getBaseRevisionId() {
        return baseRevisionId;
    }

    public void setBaseRevisionId(String baseRevisionId) {
        this.baseRevisionId = baseRevisionId;
    }

    public List<Integer> getSelectedSequenceNumbers() {
        return selectedSequenceNumbers;
    }

    public void setSelectedSequenceNumbers(List<Integer> selectedSequenceNumbers) {
        this.selectedSequenceNumbers = selectedSequenceNumbers != null ? selectedSequenceNumbers : new ArrayList<>();
    }

    public List<String> getSelectedSequenceGroupIds() {
        return selectedSequenceGroupIds;
    }

    public void setSelectedSequenceGroupIds(List<String> selectedSequenceGroupIds) {
        this.selectedSequenceGroupIds = selectedSequenceGroupIds != null ? selectedSequenceGroupIds : new ArrayList<>();
    }
}
