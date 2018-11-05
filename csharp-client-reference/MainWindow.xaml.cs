using System.Windows;
using EmpowerOps.Volition.DTO;
using Grpc.Core;

namespace EmpowerOps.Volition.RefClient
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        private readonly Optimizer.OptimizerClient _client; 
        
        public MainWindow()
        {
            //https://grpc.io/docs/quickstart/csharp.html#update-the-client
            var channel = new Channel("127.0.0.1:5550", ChannelCredentials.Insecure);
            _client = new Optimizer.OptimizerClient(channel);

            InitializeComponent();
        }

        private async void StartOptimization_Click(object sender, RoutedEventArgs e)
        {
            var message = new StartOptimizationCommandDTO();

            StartOptimizationResponseDTO response = await _client.startOptimizationAsync(message);

            MessageBox.Show($"got response: {response.ToString()}");
        }
    }
}
