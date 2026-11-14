import { describe, expect, it } from 'vitest'
import html from '../../index.html?raw'
import nginx from '../../nginx.conf?raw'
import headers from '../../security-headers.conf?raw'

/**
 * SEC-01: nginx serves the app with a strict Content-Security-Policy (script-src 'self',
 * no inline scripts), so the page must not depend on anything it forbids.
 */
describe('content security policy', () => {
  const policy = /Content-Security-Policy "([^"]+)"/.exec(headers)?.[1] ?? ''

  it('allows scripts from this origin only, and no framing', () => {
    expect(policy).toContain("script-src 'self'")
    expect(policy).not.toContain('unsafe-inline')
    expect(policy).not.toContain('unsafe-eval')
    expect(policy).toContain("frame-ancestors 'none'")
    expect(policy).toContain("object-src 'none'")
  })

  it('index.html has no inline scripts, styles or event handlers', () => {
    const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
    expect(scripts.length).toBeGreaterThan(0)
    for (const [, attributes = '', body = ''] of scripts) {
      expect(attributes).toMatch(/\bsrc="\//)
      expect(body.trim()).toBe('')
    }
    expect(html).not.toMatch(/<style\b/)
    expect(html).not.toMatch(/\son[a-z]+="/)
  })

  it('nginx adds the headers to the app, and leaves the API to the backend', () => {
    const include = 'include /etc/nginx/snippets/security-headers.conf'
    const blocks = nginx.split(/\n\s*location /).slice(1)
    expect(blocks.length).toBeGreaterThan(2)
    for (const block of blocks) {
      const body = block.slice(0, block.indexOf('\n    }'))
      if (body.includes('proxy_pass')) {
        expect(body, `proxied ${body.split(' ')[0] ?? ''}`).not.toContain(
          include,
        )
      } else {
        expect(body, `served ${body.split(' ')[0] ?? ''}`).toContain(include)
      }
    }
  })
})
