const token = document.querySelector('meta[name="generator-api-token"]')?.content;
const status = document.querySelector('#status');
const profiles = document.querySelector('#profiles');

async function loadProfiles() {
  try {
    const response = await fetch('/api/profiles', {
      headers: {'X-Gen2Spring-Token': token}
    });
    if (!response.ok) {
      throw new Error('profiles');
    }
    const payload = await response.json();
    for (const profile of payload.profiles) {
      const item = document.createElement('li');
      item.textContent = `${profile.id} — Java ${profile.javaVersion}`;
      profiles.append(item);
    }
    status.textContent = 'Local profiles are ready.';
  } catch {
    status.textContent = 'Local profiles could not be loaded.';
  }
}

loadProfiles();
