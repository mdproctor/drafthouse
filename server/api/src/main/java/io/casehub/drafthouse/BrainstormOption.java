package io.casehub.drafthouse;

public class BrainstormOption {

    public enum Status { LIVE, RECOMMENDED, EXPLORED, ELIMINATED, SELECTED }

    private final String id;
    private String title;
    private String description;
    private String tradeoffs;
    private Status status;

    public BrainstormOption(String id, String title, String description, String tradeoffs) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.tradeoffs = tradeoffs;
        this.status = Status.LIVE;
    }

    public String id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    public String tradeoffs() { return tradeoffs; }
    public Status status() { return status; }

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setTradeoffs(String tradeoffs) { this.tradeoffs = tradeoffs; }
    public void setStatus(Status status) { this.status = status; }
}
