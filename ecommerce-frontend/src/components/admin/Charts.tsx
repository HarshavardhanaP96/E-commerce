import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  BarElement,
  Title,
  Tooltip,
  Legend,
  ChartData,
  ChartOptions,
  ArcElement,
  PointElement,
  LineElement,
  Filler,
} from "chart.js";
import { Bar, Doughnut, Line, Pie } from "react-chartjs-2";

ChartJS.register(
  CategoryScale,
  LinearScale,
  BarElement,
  Title,
  Tooltip,
  Legend,
  ArcElement,
  PointElement,
  LineElement,
  Filler
);

ChartJS.defaults.font.family = "Inter, ui-sans-serif, system-ui, sans-serif";
ChartJS.defaults.font.size = 13;
ChartJS.defaults.font.weight = 500;
ChartJS.defaults.color = "#334155"; // slate-700: ~10:1 on white (the old slate-500 read as washed out)
ChartJS.defaults.borderColor = "#e2e8f0";
ChartJS.defaults.plugins.tooltip.backgroundColor = "#0f172a";
ChartJS.defaults.plugins.tooltip.padding = 10;
ChartJS.defaults.plugins.tooltip.cornerRadius = 6;
ChartJS.defaults.plugins.tooltip.titleFont = { weight: 600 };
ChartJS.defaults.plugins.tooltip.boxPadding = 4;
ChartJS.defaults.plugins.tooltip.bodyFont = { size: 13 };
ChartJS.defaults.plugins.legend.labels.color = "#0f172a";
ChartJS.defaults.plugins.legend.labels.font = { size: 13, weight: 500 };
ChartJS.defaults.plugins.legend.labels.usePointStyle = true;
ChartJS.defaults.plugins.legend.labels.pointStyle = "circle";

/** Light horizontal gridlines only — enough to read values without visual noise. */
const compact = new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 });
const valueAxis = {
  beginAtZero: true,
  border: { display: false },
  grid: { color: "#e2e8f0" },
  ticks: { callback: (v: string | number) => compact.format(Number(v)), maxTicksLimit: 6 },
};
const categoryAxis = { border: { display: false }, grid: { display: false }, ticks: { color: "#475569" } };

const months = ["January", "February", "March", "April", "May", "June", "July"];

interface BarChartProps {
  horizontal?: boolean;
  data_1: number[];
  data_2: number[];
  title_1: string;
  title_2: string;
  bgColor_1: string;
  bgColor_2: string;
  labels?: string[];
  /** Give the second series its own right-hand axis (e.g. revenue ₹ vs order count, which differ by orders of magnitude). */
  dualAxis?: boolean;
}

export const BarChart = ({
  data_1 = [],
  data_2 = [],
  title_1,
  title_2,
  bgColor_1,
  bgColor_2,
  horizontal = false,
  labels = months,
  dualAxis = false,
}: BarChartProps) => {
  const options: ChartOptions<"bar"> = {
    responsive: true,
    maintainAspectRatio: false,
    indexAxis: horizontal ? "y" : "x",
    plugins: {
      legend: {
        display: true,
        position: "top",
        align: "end",
        labels: { boxWidth: 8, boxHeight: 8 },
      },
      title: {
        display: false,
      },
    },

    scales: horizontal
      ? { x: valueAxis, y: categoryAxis }
      : dualAxis
        ? { x: categoryAxis, y: valueAxis, y1: { ...valueAxis, position: "right", grid: { drawOnChartArea: false } } }
        : { x: categoryAxis, y: valueAxis },
  };

  const data: ChartData<"bar", number[], string> = {
    labels,
    datasets: [
      {
        label: title_1,
        data: data_1,
        backgroundColor: bgColor_1,
        borderRadius: 4,
        barThickness: "flex",
        barPercentage: 0.9,
        categoryPercentage: 0.7,
        yAxisID: "y",
      },
      {
        label: title_2,
        data: data_2,
        backgroundColor: bgColor_2,
        borderRadius: 4,
        barThickness: "flex",
        barPercentage: 0.9,
        categoryPercentage: 0.7,
        yAxisID: dualAxis ? "y1" : "y",
      },
    ],
  };

  return (
    <div className="chart-box">
      <Bar options={options} data={data} />
    </div>
  );
};

interface DoughnutChartProps {
  labels: string[];
  data: number[];
  backgroundColor: string[];
  cutout?: number | string;
  legends?: boolean;
  offset?: number[];
}

export const DoughnutChart = ({
  labels,
  data,
  backgroundColor,
  cutout,
  legends = true,
  offset,
}: DoughnutChartProps) => {
  const doughnutData: ChartData<"doughnut", number[], string> = {
    labels,
    datasets: [
      {
        data,
        backgroundColor,
        borderWidth: 0,
        offset,
      },
    ],
  };

  const doughnutOptions: ChartOptions<"doughnut"> = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        display: legends,
        position: "bottom", // below the ring: the cards are narrow, so a side legend squeezed the donut
        labels: {
          padding: 14, boxWidth: 10, boxHeight: 10,
        },
      },
    },
    cutout,
  };

  return (
    <div className="chart-box">
      <Doughnut data={doughnutData} options={doughnutOptions} />
    </div>
  );
};

interface PieChartProps {
  labels: string[];
  data: number[];
  backgroundColor: string[];
  offset?: number[];
}
export const PieChart = ({
  labels,
  data,
  backgroundColor,
  offset,
}: PieChartProps) => {
  const pieChartData: ChartData<"pie", number[], string> = {
    labels,
    datasets: [
      {
        data,
        backgroundColor,
        borderWidth: 1,
        offset,
      },
    ],
  };

  const pieChartOptions: ChartOptions<"pie"> = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        display: false,
      },
    },
  };

  return (
    <div className="chart-box">
      <Pie data={pieChartData} options={pieChartOptions} />
    </div>
  );
};

interface LineChartProps {
  data: number[];
  label: string;
  backgroundColor: string;
  borderColor: string;
  labels?: string[];
}

export const LineChart = ({
  data,
  label,
  backgroundColor,
  borderColor,
  labels = months,
}: LineChartProps) => {
  const options: ChartOptions<"line"> = {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: "index", intersect: false },
    plugins: {
      legend: {
        display: false,
      },
      title: {
        display: false,
      },
    },

    scales: { x: categoryAxis, y: valueAxis },
  };

  const lineChartData: ChartData<"line", number[], string> = {
    labels,
    datasets: [
      {
        fill: true,
        label,
        data,
        backgroundColor,
        borderColor,
        borderWidth: 2,
        tension: 0.35,
        pointRadius: 0,
        pointHoverRadius: 4,
      },
    ],
  };

  return (
    <div className="chart-box">
      <Line options={options} data={lineChartData} />
    </div>
  );
};
