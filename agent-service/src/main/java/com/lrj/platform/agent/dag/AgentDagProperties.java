package com.lrj.platform.agent.dag;

import com.lrj.platform.agent.critique.CritiqueWeights;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.agent.dag")
public class AgentDagProperties {

    private int maxTasks = 6;
    private Replan replan = new Replan();

    public int getMaxTasks() {
        return maxTasks;
    }

    public void setMaxTasks(int maxTasks) {
        this.maxTasks = maxTasks;
    }

    public Replan getReplan() {
        return replan;
    }

    public void setReplan(Replan replan) {
        this.replan = replan;
    }

    public static class Replan {
        private boolean enabled = false;
        private int maxReplans = 1;
        private double threshold = 0.75;
        private CritiqueWeights weights = new CritiqueWeights();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxReplans() {
            return maxReplans;
        }

        public void setMaxReplans(int maxReplans) {
            this.maxReplans = maxReplans;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }

        public CritiqueWeights getWeights() {
            return weights;
        }

        public void setWeights(CritiqueWeights weights) {
            this.weights = weights;
        }
    }
}
