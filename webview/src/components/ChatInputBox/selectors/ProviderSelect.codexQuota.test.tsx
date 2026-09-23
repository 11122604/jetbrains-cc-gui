// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProviderSelect } from './ProviderSelect';

vi.mock('../../shared/ProviderModelIcon', () => ({
  ProviderModelIcon: () => <span data-testid="provider-icon" />,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: string | Record<string, unknown>) => {
      const map: Record<string, string> = {
        'providers.claude.label': 'Claude Code',
        'providers.codex.label': 'Codex',
        'settings.provider.featureComingSoon': 'Coming soon',
      };
      const defaultValue = options && typeof options === 'object' && 'defaultValue' in options
        ? String((options as Record<string, unknown>).defaultValue)
        : '';
      const interpolated = defaultValue.replace(/\{\{(\w+)\}\}/g, (_, token: string) => {
        const value = options && typeof options === 'object' ? (options as Record<string, unknown>)[token] : undefined;
        return value == null ? '' : String(value);
      });
      return map[key] ?? (interpolated || key);
    },
  }),
}));

describe('ProviderSelect Codex quota submenu', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
    window.updateCodexSubscriptionQuota = undefined;
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it.each([
    { width: 393, height: 851, menu: { x: 92, y: 420, width: 200, height: 310 }, minHeight: 120 },
    { width: 393, height: 365, menu: { x: 8, y: 8, width: 200, height: 300 }, minHeight: 120 },
    { width: 393, height: 365, menu: { x: 49, y: 8, width: 200, height: 310 }, minHeight: 120 },
  ])('keeps quota beside the menu without covering its rows in $width × $height', ({ width, height, menu, minHeight }) => {
    vi.stubGlobal('innerWidth', width);
    vi.stubGlobal('innerHeight', height);
    const originalRect = HTMLElement.prototype.getBoundingClientRect;
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('provider-dropdown')
        ? DOMRect.fromRect(menu) : originalRect.call(this);
    });
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(238);

    render(<ProviderSelect value="claude" />);
    fireEvent.click(screen.getByRole('button'), { detail: 1 });
    fireEvent.mouseEnter(screen.getByRole('menuitemradio', { name: 'Codex' }));
    const panel = screen.getByRole('tooltip');
    const left = menu.x + Number.parseFloat(panel.style.left);
    const top = menu.y + Number.parseFloat(panel.style.top);
    const panelWidth = Number.parseFloat(panel.style.width);
    const maxHeight = Number.parseFloat(panel.style.maxHeight);
    const panelHeight = Math.min(240, maxHeight);

    expect(left).toBeGreaterThanOrEqual(8);
    expect(left + panelWidth).toBeLessThanOrEqual(width - 8);
    expect(top).toBeGreaterThanOrEqual(8);
    expect(top + panelHeight).toBeLessThanOrEqual(height - 8);
    expect(maxHeight).toBeGreaterThanOrEqual(minHeight);
    const overlapWidth = Math.min(left + panelWidth, menu.x + menu.width) - Math.max(left, menu.x);
    const overlapHeight = Math.min(top + panelHeight, menu.y + menu.height) - Math.max(top, menu.y);
    expect(overlapWidth <= 0 || overlapHeight <= 0).toBe(true);
  });

  it('only subscribes while quota is visible and requests fresh data on reopening', () => {
    render(<ProviderSelect value="claude" />);
    expect(window.updateCodexSubscriptionQuota).toBeUndefined();
    fireEvent.click(screen.getByRole('button'), { detail: 1 });
    const codex = screen.getByRole('menuitemradio', { name: 'Codex' });
    fireEvent.mouseEnter(codex);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    fireEvent.mouseEnter(codex);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);

    fireEvent.mouseEnter(screen.getByRole('menuitemradio', { name: 'Claude Code' }));
    expect(screen.getByRole('tooltip')).toBeTruthy();
    fireEvent.mouseLeave(document.querySelector('.provider-dropdown')!);
    expect(screen.queryByRole('tooltip')).toBeNull();
    act(() => {
      window.updateCodexSubscriptionQuota?.(JSON.stringify({
        status: 'ok', fetchedAt: 1710000000000,
        windows: { fiveHour: { remainingPercent: 20 }, weekly: { remainingPercent: 32 } },
      }));
    });
    fireEvent.mouseEnter(codex);
    expect(window.sendToJava).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('tooltip').textContent).toContain('Loading...');
    expect(screen.queryByText(/20% remaining/)).toBeNull();
  });

  it('opens quota with keyboard focus and restores the trigger on Escape or selection', () => {
    const onChange = vi.fn();
    render(<ProviderSelect value="claude" onChange={onChange} />);
    const trigger = screen.getByRole('button');
    act(() => trigger.focus());
    fireEvent.keyDown(trigger, { key: 'ArrowDown' });
    const selected = screen.getByRole('menuitemradio', { checked: true });
    expect(document.activeElement).toBe(selected);
    fireEvent.keyDown(selected, { key: 'ArrowDown' });
    const codex = screen.getByRole('menuitemradio', { name: 'Codex' });
    expect(document.activeElement).toBe(codex);
    const quota = screen.getByRole('tooltip');
    expect(codex.getAttribute('aria-describedby')).toBe(quota.id);
    fireEvent.mouseLeave(document.querySelector('.provider-dropdown')!);
    expect(screen.getByRole('tooltip')).toBe(quota);
    fireEvent.keyDown(codex, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);

    fireEvent.keyDown(trigger, { key: 'ArrowDown' });
    fireEvent.keyDown(screen.getByRole('menuitemradio', { checked: true }), { key: 'ArrowDown' });
    fireEvent.keyDown(screen.getByRole('menuitemradio', { name: 'Codex' }), { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('codex');
    expect(document.activeElement).toBe(trigger);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  it('keeps quota open and leaves scrolling keys to its focused contents', () => {
    render(<div tabIndex={0}><ProviderSelect value="claude" /></div>);
    const trigger = screen.getByRole('button');
    act(() => trigger.focus());
    fireEvent.click(trigger, { detail: 1 });
    fireEvent.mouseEnter(screen.getByRole('menuitemradio', { name: 'Codex' }));
    const quota = screen.getByRole('tooltip');
    act(() => quota.focus());
    expect(document.activeElement).toBe(quota);
    fireEvent.mouseLeave(document.querySelector('.provider-dropdown')!);
    expect(screen.getByRole('tooltip')).toBe(quota);
    expect(fireEvent.keyDown(quota, { key: 'ArrowDown' })).toBe(true);
    expect(fireEvent.keyDown(quota, { key: 'End' })).toBe(true);
    expect(document.activeElement).toBe(quota);
    fireEvent.keyDown(quota, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('shows a submenu for Codex with quota details', async () => {
    render(<ProviderSelect value="claude" />);

    fireEvent.click(screen.getByRole('button'));
    const providerDropdown = document.querySelector('.provider-dropdown') as HTMLElement;
    expect(providerDropdown.style.overflowX).toBe('');
    const codexRow = screen.getByText('Codex').closest('.selector-option')!;
    expect(codexRow.querySelector('.codicon-chevron-right')).toBeTruthy();

    fireEvent.mouseEnter(codexRow);
    expect(window.sendToJava).toHaveBeenCalledWith('get_codex_subscription_quota:');

    act(() => {
      window.updateCodexSubscriptionQuota?.(JSON.stringify({
        status: 'ok',
        fetchedAt: 1710000000000,
        source: 'local_history',
        windows: {
          fiveHour: {
            windowLabel: '5h',
            windowHours: 5,
            usedPercent: 80,
            remainingPercent: 20,
            resetsAt: 1748676960000,
            usedTokens: 0,
            limitTokens: null,
            remainingTokens: null,
            usedCost: 1.2,
            sessionCount: 3,
            lastUpdated: 1710000000000,
            source: 'local_history',
          },
          weekly: {
            windowLabel: 'weekly',
            windowHours: 168,
            usedPercent: 68,
            remainingPercent: 32,
            resetsAt: 1748763360000,
            usedTokens: 0,
            limitTokens: null,
            remainingTokens: null,
            usedCost: 3.4,
            sessionCount: 9,
            lastUpdated: 1710000000000,
            source: 'local_history',
          },
        },
      }));
    });

    const submenu = await screen.findByText('Codex quota');
    expect(submenu).toBeTruthy();
    expect(within(providerDropdown).getByText('5h usage')).toBeTruthy();
    expect(within(providerDropdown).getByText(/20% remaining \u00b7 Resets /)).toBeTruthy();
    expect(within(providerDropdown).getByText('Weekly usage')).toBeTruthy();
    expect(within(providerDropdown).getByText(/32% remaining \u00b7 Resets /)).toBeTruthy();
    expect(screen.queryByText(/Source:/)).toBeNull();
    expect(screen.queryByText(/Balance refreshed at/)).toBeNull();
  });

  it('shows unavailable rows when quota windows have no values', async () => {
    render(<ProviderSelect value="claude" />);

    fireEvent.click(screen.getByRole('button'));
    const codexRow = screen.getByText('Codex').closest('.selector-option')!;
    fireEvent.mouseEnter(codexRow);

    act(() => {
      window.updateCodexSubscriptionQuota?.(JSON.stringify({
        status: 'unavailable',
        fetchedAt: 1710000000000,
        windows: {
          fiveHour: {
            windowLabel: '5h',
            windowHours: 5,
            usedPercent: null,
            remainingPercent: null,
            resetsAt: null,
            usedTokens: 0,
            limitTokens: null,
            remainingTokens: null,
          },
          weekly: {
            windowLabel: 'weekly',
            windowHours: 168,
            usedPercent: null,
            remainingPercent: null,
            resetsAt: null,
            usedTokens: 0,
            limitTokens: null,
            remainingTokens: null,
          },
        },
      }));
    });

    const providerDropdown = document.querySelector('.provider-dropdown') as HTMLElement;
    expect(await within(providerDropdown).findByText('Codex quota')).toBeTruthy();
    expect(within(providerDropdown).getAllByText('Unavailable')).toHaveLength(3);
    expect(screen.queryByText('0 used')).toBeNull();
    expect(screen.queryByText(/Resets /)).toBeNull();
  });

  it('shows a dedicated message and hides window rows in API key mode', async () => {
    render(<ProviderSelect value="claude" />);

    fireEvent.click(screen.getByRole('button'));
    const codexRow = screen.getByText('Codex').closest('.selector-option')!;
    fireEvent.mouseEnter(codexRow);

    act(() => {
      window.updateCodexSubscriptionQuota?.(JSON.stringify({
        status: 'unavailable',
        fetchedAt: 1710000000000,
        source: 'none',
        reasonCode: 'api_key_mode',
        error: 'API key mode has no subscription quota',
        windows: {
          fiveHour: {
            windowLabel: '5h',
            windowHours: 5,
            usedPercent: null,
            remainingPercent: null,
            resetsAt: null,
            usedTokens: 0,
            limitTokens: null,
            remainingTokens: null,
          },
          weekly: {
            windowLabel: 'weekly',
            windowHours: 168,
            usedPercent: null,
            remainingPercent: null,
            resetsAt: null,
            usedTokens: 0,
            limitTokens: null,
            remainingTokens: null,
          },
        },
      }));
    });

    const providerDropdown = document.querySelector('.provider-dropdown') as HTMLElement;
    expect(await within(providerDropdown).findByText('Codex quota')).toBeTruthy();
    expect(within(providerDropdown).getByText('API key mode has no subscription quota')).toBeTruthy();
    expect(within(providerDropdown).queryByText('5h usage')).toBeNull();
    expect(within(providerDropdown).queryByText('Weekly usage')).toBeNull();
    expect(within(providerDropdown).queryByText('Unavailable')).toBeNull();
  });
});
