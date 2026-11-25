function e(e){let t=e.head;if(!t||t.querySelector(`style[data-ooxml-comment-styles]`))return;let n=e.createElement(`style`);n.dataset.ooxmlCommentStyles=``,n.textContent=`
:where(.ooxml-comment-marker) {
  padding: 0;
  border: 0;
  cursor: pointer;
  pointer-events: auto;
  background: transparent;
  box-shadow: none;
  color: var(--ooxml-comment-marker-color, var(--ooxml-comment-author-accent));
}
:where([data-ooxml-comment-highlight]) {
  position: absolute;
  pointer-events: none;
  background: var(--ooxml-comment-highlight, color-mix(in srgb, var(--ooxml-comment-author-accent) 18%, transparent));
  transform-origin: top left;
}
:where([data-ooxml-comment-highlight][data-active="true"]) {
  background: var(--ooxml-comment-highlight-active, color-mix(in srgb, var(--ooxml-comment-author-accent) 34%, transparent));
}
:where([data-ooxml-comment-target]) {
  position: absolute;
  box-sizing: border-box;
  pointer-events: none;
  border-style: solid;
  border-color: var(--ooxml-comment-target-border, #2563eb);
  background: var(--ooxml-comment-target-background, rgba(37, 99, 235, .06));
  transform-origin: center;
}
:where(.ooxml-comment-card) {
  position: relative;
  display: block;
  width: 100%;
  margin: 0 0 var(--ooxml-comment-card-gap, .42em);
  box-sizing: border-box;
  padding: var(--ooxml-comment-card-padding, .56em .68em);
  border: 0;
  border-radius: var(--ooxml-comment-card-radius, .3em);
  text-align: start;
  cursor: default;
  font: inherit;
  outline: none;
  background: var(--ooxml-comment-card-background, #fff);
  box-shadow: var(--ooxml-comment-card-shadow, none);
}
:where(button.ooxml-comment-card) {
  cursor: pointer;
}
:where(.ooxml-comment-card[data-standalone="true"]) {
  position: absolute;
  width: max-content;
  max-width: 100%;
  margin: 0;
  z-index: 3;
  pointer-events: none;
  overflow: hidden;
  font-size: 13px;
}
:where(.ooxml-comment-card[data-active="true"]) {
  background: var(--ooxml-comment-card-active-background, #eff6ff);
  box-shadow: var(--ooxml-comment-card-active-shadow, none);
}
:where(.ooxml-comment-card[data-focused="true"]) {
  box-shadow: var(--ooxml-comment-card-focus-shadow, 0 0 0 .12em rgba(37, 99, 235, .65));
}
:where(.ooxml-comment-card [data-ooxml-comment-part="content"]) {
  min-width: 0;
  flex: 1;
}
:where(.ooxml-comment-card [data-ooxml-comment-part="identity"]) {
  display: flex;
  align-items: baseline;
  gap: .48em;
  min-width: 0;
}
:where(.ooxml-comment-card__author) {
  min-width: 0;
  font: 700 .84em/1.3 var(--ooxml-comment-font-family, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif);
  color: var(--ooxml-comment-author-color, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
:where(.ooxml-comment-card__date) {
  font: 500 .66em/1.35 var(--ooxml-comment-date-font-family, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  color: var(--ooxml-comment-muted-color, #64748b);
  white-space: nowrap;
}
:where(.ooxml-comment-card__body) {
  margin-top: .28em;
  font: 400 .84em/1.45 var(--ooxml-comment-font-family, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif);
  color: var(--ooxml-comment-body-color, #334155);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
:where(.ooxml-comment-card__reply) {
  margin: .55em 0 0 .45em;
  padding: .08em 0 0 .65em;
  border-left: .08em solid var(--ooxml-comment-reply-border, rgba(100, 116, 139, .24));
}
:where(.ooxml-comment-card [data-ooxml-comment-part="frame"]) {
  position: absolute;
  inset: 0;
  box-sizing: border-box;
  pointer-events: none;
  border-radius: inherit;
  border: var(--ooxml-comment-card-border, 1px solid rgba(148, 163, 184, .34));
  border-left: var(--ooxml-comment-card-border-left, .14em solid var(--ooxml-comment-author-accent));
  border-right: var(--ooxml-comment-card-border-right, var(--ooxml-comment-card-border, 1px solid rgba(148, 163, 184, .34)));
}
:where(.ooxml-comment-card[data-active="true"] [data-ooxml-comment-part="frame"]) {
  border: var(--ooxml-comment-card-active-border, 1px solid rgba(37, 99, 235, .5));
  border-left: var(--ooxml-comment-card-border-left, .14em solid var(--ooxml-comment-author-accent));
  border-right: var(--ooxml-comment-card-border-right, var(--ooxml-comment-card-active-border, 1px solid rgba(37, 99, 235, .5)));
}
:where([data-ooxml-comment-ui="margin"] > [data-ooxml-comment-item]) {
  position: absolute;
  left: 0;
  padding: 0 .14em;
  box-sizing: border-box;
  transform-origin: 0 0;
}
:where([data-ooxml-comment-ui="margin"] > [data-ooxml-comment-item]:last-child > .ooxml-comment-card) {
  margin-bottom: 0;
}
`,t.appendChild(n)}function t(e){if(!e)return`#2563eb`;let t=2166136261;for(let n of e.normalize(`NFKC`))t^=n.codePointAt(0)??0,t=Math.imul(t,16777619);return`hsl(${(t>>>0)%360} 68% 42%)`}function n(e,t=``){let n=e.createElement(`div`);return n.style.cssText=t,n}function r(e){if(!e)return;let t=new Date(e);return Number.isFinite(t.getTime())?new Intl.DateTimeFormat(void 0,{dateStyle:`medium`,timeStyle:`short`}).format(t):e}function i(e,t,i){let a=e.ownerDocument,o=n(a);i&&o.setAttribute(`class`,`ooxml-comment-card__reply`),o.dataset.ooxmlCommentPart=i?`reply`:`comment`;let s=n(a);s.dataset.ooxmlCommentPart=`content`;let c=n(a);c.dataset.ooxmlCommentPart=`identity`;let l=n(a);l.setAttribute(`class`,`ooxml-comment-card__author`),l.dataset.ooxmlCommentPart=`author`,l.textContent=t.author||`Comment`,c.appendChild(l);let u=r(t.date);if(u){let e=n(a);e.setAttribute(`class`,`ooxml-comment-card__date`),e.dataset.ooxmlCommentPart=`date`,e.textContent=u,e.setAttribute(`title`,t.date),c.appendChild(e)}let d=n(a);d.setAttribute(`class`,`ooxml-comment-card__body`),d.dataset.ooxmlCommentPart=`body`,d.textContent=t.text,s.appendChild(c),s.appendChild(d),o.appendChild(s),e.appendChild(o)}function a(r,a,o={}){e(r.ownerDocument);let s=o.active??!1,c=o.focused??!1,l=o.interactive??r.tagName===`BUTTON`,u=t(a.root.author);r.setAttribute(`class`,`ooxml-comment-card`),r.dataset.ooxmlCommentId=a.occurrenceKey,r.dataset.active=String(s),r.dataset.focused=String(c),r.dataset.standalone=String(o.standalone??!1),r.dataset.ooxmlCommentCard=``,l?r.setAttribute(`aria-pressed`,String(s)):r.removeAttribute(`aria-pressed`),r.style.cssText=`--ooxml-comment-author-accent:${u};`,r.replaceChildren(),i(r,a.root,!1);for(let e of a.replies)i(r,e,!0);let d=n(r.ownerDocument);d.dataset.ooxmlCommentPart=`frame`,d.setAttribute(`aria-hidden`,`true`),r.appendChild(d)}export{a as paintReadOnlyCommentCard};
//# sourceMappingURL=comment-ui-runtime-BsK8pmUT-B9wHKUWh.js.map