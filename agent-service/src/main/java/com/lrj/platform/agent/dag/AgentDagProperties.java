package com.lrj.platform.agent.dag;

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
        private Weights weights = new Weights();

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

        public Weights getWeights() {
            return weights;
        }

        public void setWeights(Weights weights) {
            this.weights = weights;
        }
    }

    public static class Weights {
        private double correctness = 0.5;
        private double completeness = 0.35;
        private double clarity = 0.15;

        public double getCorrectness() {
            return correctness;
        }

        public void setCorrectness(double correctness) {
            this.correctness = correctness;
        }

        public double getCompleteness() {
            return completeness;
        }

        public void setCompleteness(double completeness) {
            this.completeness = completeness;
        }

        public double getClarity() {
            return clarity;
        }

        public void setClarity(double clarity) {
            this.clarity = clarity;
        }
    }
}
