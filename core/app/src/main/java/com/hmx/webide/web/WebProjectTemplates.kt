/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hmx.webide.web

/**
 * Starter templates for new web projects. Each template generates a set of
 * files (relative path -> content) for the new project directory.
 */
object WebProjectTemplates {

  data class Template(
    val name: String,
    val description: String,
    val files: (projectName: String) -> Map<String, String>
  )

  val all: List<Template> = listOf(
    Template("Blank", "A single empty index.html page") { name -> blank(name) },
    Template("Basic", "index.html + style.css + script.js") { name -> basic(name) },
    Template("Todo", "A small interactive todo list app") { name -> todo(name) }
  )

  private fun blank(name: String): Map<String, String> {
    return mapOf("index.html" to """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$name</title>
</head>
<body>
  <h1>Hello from $name!</h1>
</body>
</html>
""")
  }

  private fun basic(name: String): Map<String, String> {
    return mapOf(
      "index.html" to """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$name</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main class="card">
    <h1>Hello from $name!</h1>
    <p id="status">Loading...</p>
    <button id="btn">Click me</button>
  </main>
  <script src="script.js"></script>
</body>
</html>
""",
      "style.css" to """* { box-sizing: border-box; }
body {
  font-family: system-ui, sans-serif;
  display: grid;
  place-items: center;
  min-height: 100vh;
  margin: 0;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
}
.card {
  background: rgba(0, 0, 0, 0.35);
  padding: 2rem 3rem;
  border-radius: 1rem;
  text-align: center;
}
button {
  font-size: 1rem;
  padding: 0.5rem 1.5rem;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
}
""",
      "script.js" to """const status = document.getElementById('status');
const btn = document.getElementById('btn');

status.textContent = 'Page loaded at ' + new Date().toLocaleTimeString();

let count = 0;
btn.addEventListener('click', () => {
  count++;
  status.textContent = 'Clicked ' + count + ' time' + (count === 1 ? '' : 's');
});
"""
    )
  }

  private fun todo(name: String): Map<String, String> {
    return mapOf(
      "index.html" to """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$name - Todo</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main class="app">
    <h1>Todo</h1>
    <form id="form">
      <input id="input" placeholder="What needs to be done?" autocomplete="off" required>
      <button type="submit">Add</button>
    </form>
    <ul id="list"></ul>
  </main>
  <script src="script.js"></script>
</body>
</html>
""",
      "style.css" to """* { box-sizing: border-box; }
body {
  font-family: system-ui, sans-serif;
  margin: 0;
  background: #f4f4f5;
  display: grid;
  place-items: center;
  min-height: 100vh;
}
.app {
  background: #fff;
  width: min(420px, 90vw);
  border-radius: 1rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
  padding: 1.5rem;
}
form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 0.4rem; }
button { padding: 0.5rem 1rem; border: none; border-radius: 0.4rem; background: #6366f1; color: #fff; cursor: pointer; }
ul { list-style: none; padding: 0; margin: 0; }
li { display: flex; justify-content: space-between; padding: 0.5rem 0; border-bottom: 1px solid #eee; }
li.done { text-decoration: line-through; color: #999; }
""",
      "script.js" to """const form = document.getElementById('form');
const input = document.getElementById('input');
const list = document.getElementById('list');

form.addEventListener('submit', (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  const li = document.createElement('li');
  li.textContent = text;
  li.addEventListener('click', () => li.classList.toggle('done'));
  list.appendChild(li);
  input.value = '';
  input.focus();
});
"""
    )
  }
}