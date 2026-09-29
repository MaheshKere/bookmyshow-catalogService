import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToString } from 'react-dom/server';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { createServer } from 'vite';
import { store } from '../src/store/store.js';

test('real React/Bootstrap pages render without runtime component errors', async () => {
  // Existing Vite transforms JSX for this small Node rendering check; no browser-test framework.
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
  try {
    const { default: App } = await server.ssrLoadModule('/src/App.jsx');
    const { default: MovieCard } = await server.ssrLoadModule('/src/components/MovieCard.jsx');
    const render = (element, path) => renderToString(React.createElement(Provider, { store },
      React.createElement(MemoryRouter, { initialEntries: [path] }, element)));
    assert.match(render(React.createElement(App), '/login'), /Sign in for your next show/);
    assert.match(render(React.createElement(App), '/register'), /Create an account/);
    assert.match(render(React.createElement(App), '/movies'), /Explore movies/);
    assert.match(render(React.createElement(MovieCard, { movie: { id: 1, title: 'Contract movie', genre: 'Drama', language: 'English', durationMinutes: 120, active: true } }), '/movies'), /Contract movie/);
  } finally { await server.close(); }
});
