// FoolioTitles - Development by DeJIoVoy
package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.text.Lang;

import java.util.List;
import java.util.function.IntConsumer;

/** Меню со списком на несколько страниц. */
public abstract class PagedMenu extends Menu {

    protected final int page;

    protected PagedMenu(FoolioTitles plugin, Player viewer, String id, int page) {
        super(plugin, viewer, id);
        this.page = Math.max(0, page);
    }

    public int page() {
        return page;
    }

    protected abstract int total();

    protected int pages() {
        int perPage = Math.max(1, cfg().contentSlots(id, cfg().size(id)).size());
        return Math.max(1, (total() + perPage - 1) / perPage);
    }

    /** Текущая страница с учётом того, что список мог уменьшиться. */
    protected int currentPage() {
        return Math.min(page, pages() - 1);
    }

    @Override
    protected TagResolver titleResolver() {
        return TagResolver.resolver(Lang.num("page", currentPage() + 1), Lang.num("pages", pages()));
    }

    /** Расставить элементы текущей страницы: render(index в списке, слот). */
    protected void fillPage(IndexSlotConsumer consumer) {
        List<Integer> slots = contentSlots();
        int start = currentPage() * slots.size();
        for (int i = 0; i < slots.size() && start + i < total(); i++) {
            consumer.accept(start + i, slots.get(i));
        }
    }

    /** Кнопки «назад/вперёд» по страницам. */
    protected void pageButtons(TagResolver r, IntConsumer openPage) {
        int current = currentPage();
        if (current > 0) {
            place("previous", r, type -> {
                click();
                openPage.accept(current - 1);
            });
        }
        if (current < pages() - 1) {
            place("next", r, type -> {
                click();
                openPage.accept(current + 1);
            });
        }
    }

    @FunctionalInterface
    protected interface IndexSlotConsumer {
        void accept(int index, int slot);
    }
}
