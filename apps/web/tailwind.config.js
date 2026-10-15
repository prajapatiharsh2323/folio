import tailwindcssAnimate from "tailwindcss-animate";

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
      },
      boxShadow: {
        xs: "0 1px 2px 0 rgba(0, 0, 0, 0.05)"
      },
      backdropBlur: {
        xs: "2px"
      }
    }
  },
  plugins: [tailwindcssAnimate]
};

