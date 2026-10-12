/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      colors: {
        bg: "var(--bg)",
        surface: "var(--surface)",
        text: "var(--text)",
        muted: "var(--muted)",
        border: "var(--border)",
        accent: "var(--accent)",
        highlight: {
          yellow: "var(--hl-yellow)",
          green: "var(--hl-green)",
          blue: "var(--hl-blue)",
          pink: "var(--hl-pink)"
        }
      },
      borderRadius: {
        card: "12px",
        sheet: "20px"
      }
    }
  },
  plugins: []
};
