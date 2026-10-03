package ru.nagumo.verity.server.companion;

/**
 * Данные компаньона одного игрока-владельца: текущее имя (раздел 2 ТЗ) и выбранный
 * пресет характера (раздел 8 ТЗ). Пока хранится только в памяти — персистентность
 * (файл/БД) добавим отдельно, когда появится реальная логика в модуле brain.
 *
 * Ревизия 10.09.2026: пресет "отношение к посторонним" и ссылка на живую сущность
 * компаньона в мире убраны — компаньон больше не физическая сущность, а общение
 * всегда приватное (владелец ↔ его компаньон), понятия "посторонний" не существует.
 */
public final class CompanionData {

    public static final String DEFAULT_STYLE = "friendly";

    private String name;
    private String style = DEFAULT_STYLE;

    public CompanionData(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }
}
