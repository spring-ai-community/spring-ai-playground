// Fill the download counter under the installer badges from the GitHub releases
// API. Counts are summed per installer across every release, keyed by the same
// data-pattern the badges already use, so labels stay in sync with the badges.
// Checksums, blockmaps and update manifests are excluded because their names do
// not end with an installer pattern. Benign no-op on pages without the block;
// if the fetch fails or is rate limited, the block stays hidden.
(function () {
  var CACHE_KEY = 'saip-download-stats';
  var CACHE_TTL_MS = 30 * 60 * 1000;

  var block = document.getElementById('dl-stats');
  var totalEl = document.getElementById('dl-stats-total');
  var listEl = document.getElementById('dl-stats-list');
  if (!block || !totalEl || !listEl) return;

  var badges = Array.prototype.slice.call(document.querySelectorAll('a[data-pattern]'));
  if (badges.length === 0) return;

  function readCache() {
    try {
      var raw = sessionStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      var entry = JSON.parse(raw);
      if (!entry || Date.now() - entry.at > CACHE_TTL_MS) return null;
      return entry.counts;
    } catch (e) {
      return null;
    }
  }

  function writeCache(counts) {
    try {
      sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), counts: counts }));
    } catch (e) {
      // storage unavailable, fall back to fetching again next visit
    }
  }

  function countsFromReleases(releases) {
    var counts = {};
    badges.forEach(function (badge) {
      counts[badge.dataset.pattern] = 0;
    });
    releases.forEach(function (release) {
      (release.assets || []).forEach(function (asset) {
        var name = asset.name || '';
        badges.forEach(function (badge) {
          var pattern = badge.dataset.pattern;
          if (name.slice(-pattern.length) === pattern) {
            counts[pattern] += asset.download_count || 0;
          }
        });
      });
    });
    return counts;
  }

  function render(counts) {
    var total = 0;
    badges.forEach(function (badge) {
      total += counts[badge.dataset.pattern] || 0;
    });
    if (total === 0) return;

    totalEl.textContent = '';
    var figure = document.createElement('strong');
    figure.className = 'dl-stats__figure';
    figure.textContent = total.toLocaleString();
    totalEl.appendChild(figure);
    totalEl.appendChild(document.createTextNode(' installer downloads'));
    listEl.textContent = '';
    badges.forEach(function (badge) {
      var count = counts[badge.dataset.pattern] || 0;
      var share = Math.round((count / total) * 100);
      var row = document.createElement('li');
      var label = document.createElement('span');
      label.className = 'dl-stats__label';
      label.textContent = badge.dataset.label;
      var value = document.createElement('span');
      value.className = 'dl-stats__value';
      value.textContent = count.toLocaleString() + ' (' + share + '%)';
      row.appendChild(label);
      row.appendChild(value);
      listEl.appendChild(row);
    });
    block.hidden = false;
  }

  var cached = readCache();
  if (cached) {
    render(cached);
    return;
  }

  fetch('https://api.github.com/repos/spring-ai-community/spring-ai-playground/releases?per_page=100', {
    headers: { Accept: 'application/vnd.github+json' }
  })
    .then(function (response) {
      if (!response.ok) throw new Error('release fetch failed');
      return response.json();
    })
    .then(function (releases) {
      if (!Array.isArray(releases)) return;
      var counts = countsFromReleases(releases);
      writeCache(counts);
      render(counts);
    })
    .catch(function () {
      // leave the counter hidden rather than showing a broken figure
    });
})();
