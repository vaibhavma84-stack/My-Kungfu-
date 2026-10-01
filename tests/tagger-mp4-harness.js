// Lets the browser module be tested under Node, which has no `import` for a
// CommonJS test file. The module itself stays plain ES modules with no build
// step, because a build step is a thing that breaks.
module.exports = new Proxy({}, {
  get(_, name) {
    return async (...args) => {
      const mod = await import('../tagger/mp4.js');
      return mod[name](...args);
    };
  },
});
