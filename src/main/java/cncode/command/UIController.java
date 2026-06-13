package cncode.command;

public interface UIController {
    void system(String message);

    void error(String message);

    void setPlanOnly(boolean planOnly);

    void clear();

    void compact();
}
